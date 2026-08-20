package io.github.chenchen913.baibai

import android.content.Context
import android.util.Log
import android.webkit.WebSettings
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 瓦片离线磁盘缓存（P0 / 离线优先）。
 * - key = 规范化 URL（TileMath.cacheKey，去主机与子域数字）；
 * - 读取走缓存优先（瓦片内容不可变，命中即返回，离线零依赖网络）；
 * - 写入原子替换（先写 .tmp 再改名）；
 * - 按字节上限 LRU 淘汰（64MB 默认）。
 *
 * 并发设计（地图审核报告 P1/P3/P4/P5）：
 * - get 无全局锁：预载/下载不再饿死实时瓦片请求（P1 锁饥饿根因修复）；
 * - 网络下载只锁单个 cacheKey：同一张瓦片不重复下载，不同瓦片互不阻塞；
 * - 预载走独立 3 线程池（限流防高德风控），与 WebView 实时请求完全解耦；
 * - UA 与 WebView 完全一致（浏览器默认 UA，不加自定义后缀——自定义后缀易触发高德风控）；
 * - 全部失败路径打 logcat（tag=BaibaiMap），真机可定位（P4）。
 *
 * 风控占位图防护（真机实测根因，2026-08-20）：
 * 高德对非浏览器指纹请求返回 HTTP 200 的 1×1 米色占位 PNG（179 字节）——
 * 「假成功」会毒化缓存、骗过 tileload 计数、让回退看门狗永不触发，地图永远空白。
 * 凡检出占位图：不入缓存、视同未命中，已中毒的缓存文件读取时自清理。
 */
class TileCache(context: Context, private val maxBytes: Long = 64L * 1024 * 1024) {

    private val dir: File = File(context.cacheDir, "tiles").apply { mkdirs() }

    /** 与 WebView 完全一致的 UA（P5 + 风控修复）：不加自定义后缀，自定义 UA 尾巴是触发高德风控的高危指纹 */
    val userAgent: String = WebSettings.getDefaultUserAgent(context)
        ?: "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /** 每个 cacheKey 一把小锁（P1）：锁粒度 = 单张瓦片 */
    private val keyLocks = ConcurrentHashMap<String, Any>()

    /** trim 串行化：避免多个 put 并发淘汰互相踩 */
    private val trimLock = Any()

    fun fileFor(url: String): File =
        File(dir, Integer.toHexString(TileMath.cacheKey(url).hashCode()) + ".tile")

    /** 只读缓存：无锁（P1），命中即返回，绝不因预载/下载被阻塞；读到风控占位图则自清理并视同未命中 */
    fun get(url: String): ByteArray? {
        val f = fileFor(url)
        if (!f.exists()) return null
        val b = runCatching { f.readBytes() }.getOrNull() ?: return null
        if (isPlaceholderTile(b)) {
            // 已中毒的历史缓存（旧版本会把占位图写进来）：删除后视同未命中
            Log.w(TAG, "清理风控占位图缓存：" + url)
            runCatching { f.delete() }
            return null
        }
        return b
    }

    /** 原子写入（.tmp → rename）；调用方已持单 key 锁 */
    private fun putLocked(url: String, bytes: ByteArray) {
        runCatching {
            val f = fileFor(url)
            val tmp = File(dir, f.name + ".tmp")
            FileOutputStream(tmp).use { it.write(bytes) }
            tmp.renameTo(f)
        }
        trim()
    }

    /** 缓存优先下载：命中直接返回；未命中走网络（只锁单 key），成功写缓存；失败兜底返回缓存或 null */
    fun download(url: String): ByteArray? {
        get(url)?.let { return it }
        val key = TileMath.cacheKey(url)
        val lock = keyLocks.computeIfAbsent(key) { Any() }
        try {
            synchronized(lock) {
                get(url)?.let { return it } // double-check：等锁期间可能已被别的线程写入
                val bytes = httpGet(url) ?: return get(url)
                if (bytes.isNotEmpty() && !isPlaceholderTile(bytes)) {
                    putLocked(url, bytes)
                    return bytes
                }
                if (bytes.isNotEmpty()) Log.w(TAG, "风控占位图不入缓存：" + url)
                return get(url)
            }
        } finally {
            // 用完即摘，防 keyLocks 无限增长（不再整体 clear，避免并发窗口）；
            // 极窄窗口内同 key 最多出现两个锁对象 → 最多一次重复下载，瓦片内容幂等、写入原子替换，无害
            keyLocks.remove(key, lock)
        }
    }

    /**
     * 异步回填（拦截层用，fire-and-forget）：
     * WebView 未命中缓存时放行其自取（与浏览器行为一致，不触发风控），后台同时尝试下载回填，
     * 下次同瓦片请求即命中。即使下载到占位图，download 的占位图检测也会拒写缓存，无害。
     */
    fun backfillAsync(url: String) {
        livePool.submit<ByteArray?> { runCatching { download(url) }.getOrNull() }
    }

    /** 下载 + 异常兜底（预载线程内用，避免任务抛异常；也消除 submit 的 SAM 重载歧义） */
    private fun downloadQuiet(url: String): Boolean = try {
        download(url) != null
    } catch (e: Exception) {
        Log.w(TAG, "预载瓦片异常：" + url + "，" + e.message)
        false
    }

    /** 预载一批瓦片（P1：3 线程并发 + 每 key 独立锁，绝不阻塞实时请求）；返回成功张数 */
    fun preload(urls: List<String>, onProgress: ((done: Int, total: Int) -> Unit)? = null): Int {
        val total = urls.size
        val done = AtomicInteger(0)
        val ok = AtomicInteger(0)
        val futures = urls.map { u ->
            preloadPool.submit {
                if (downloadQuiet(u)) ok.incrementAndGet()
                onProgress?.invoke(done.incrementAndGet(), total)
            }
        }
        futures.forEach { runCatching { it.get() } }
        return ok.get()
    }

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** 网络下载单张瓦片；失败打日志（P4），返回 null */
    private fun httpGet(url: String, depth: Int = 0): ByteArray? {
        if (depth > 3) return null
        val conn = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                // shouldInterceptRequest 同步调用本方法（P3 改回同步注入字节）：
                // 超时收紧到 4s/5s，死网络下尽快失败，让 tileerror→OSM 回退早触发，避免长时间占住 WebView 网络线程
                connectTimeout = 4000
                readTimeout = 5000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                // P5：高德瓦片带 Referer，贴近网页端调用习惯，降低风控风险
                if (url.contains("is.autonavi.com")) {
                    setRequestProperty("Referer", "https://webrd.is.autonavi.com/")
                }
            }
        }.getOrNull()
        if (conn == null) {
            Log.w(TAG, "瓦片连接创建失败：" + url)
            return null
        }
        return try {
            val code = conn.responseCode
            if (code == 200) {
                conn.inputStream.use { it.readBytes() }
            } else if (code in 301..308) {
                val loc = conn.getHeaderField("Location")
                if (!loc.isNullOrEmpty()) {
                    httpGet(loc, depth + 1)
                } else {
                    Log.w(TAG, "瓦片重定向无Location HTTP " + code + "：" + url)
                    null
                }
            } else {
                Log.w(TAG, "瓦片下载失败 HTTP " + code + "：" + url)
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "瓦片下载异常：" + url + "，" + e.message)
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun trim() {
        synchronized(trimLock) {
            var total = totalBytes()
            if (total <= maxBytes) return
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
            for (f in files) {
                if (total <= maxBytes) break
                total -= f.length()
                f.delete()
            }
        }
    }

    /**
     * 高德风控占位图检测：返回 HTTP 200 的 1×1 PNG（179 字节，米色）。
     * - PNG：解析 IHDR 宽高（字节 16~24 大端），任一维 ≤2 即占位；
     * - 其他格式（卫星 JPEG 等）：真实瓦片至少 1.5KB+，小于 1KB 一律视为可疑占位。
     */
    fun isPlaceholderTile(b: ByteArray): Boolean {
        if (b.size >= 8 &&
            b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()
        ) {
            if (b.size < 24) return true
            fun be32(o: Int): Int =
                ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
                    ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
            return be32(16) <= 2 || be32(20) <= 2
        }
        return b.size < 1024
    }

    companion object {
        private const val TAG = "BaibaiMap"

        /** 预载专用 3 线程池（守护线程）：并发度受限，防高德风控 */
        private val preloadPool = Executors.newFixedThreadPool(3) { r ->
            Thread(r, "baibai-tile-preload").apply { isDaemon = true }
        }

        /** 实时瓦片回填专用 2 线程池：与预载池隔离，不被 170 张预载任务饿死 */
        private val livePool = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "baibai-tile-live").apply { isDaemon = true }
        }
    }
}
