package io.github.chenchen913.baibai

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 瓦片离线磁盘缓存（P0 / 离线优先）。
 * - key = 规范化 URL（TileMath.cacheKey，去主机与子域数字）；
 * - 读取走缓存优先（瓦片内容不可变，命中即返回，离线零依赖网络）；
 * - 写入原子替换（先写 .tmp 再改名）；
 * - 按字节上限 LRU 淘汰（64MB 默认）。
 */
class TileCache(context: Context, private val maxBytes: Long = 64L * 1024 * 1024) {

    private val dir: File = File(context.cacheDir, "tiles").apply { mkdirs() }

    fun fileFor(url: String): File =
        File(dir, Integer.toHexString(TileMath.cacheKey(url).hashCode()) + ".tile")

    @Synchronized
    fun get(url: String): ByteArray? {
        val f = fileFor(url)
        if (!f.exists()) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    @Synchronized
    fun put(url: String, bytes: ByteArray) {
        runCatching {
            val f = fileFor(url)
            val tmp = File(dir, f.name + ".tmp")
            FileOutputStream(tmp).use { it.write(bytes) }
            tmp.renameTo(f)
        }
        trim()
    }
    /** 缓存优先下载：命中直接返回；未命中走网络，成功写缓存；失败返回缓存或 null */
    @Synchronized
    fun download(url: String): ByteArray? {
        get(url)?.let { return it }
        val conn = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                setRequestProperty("User-Agent", "baibai/0.1 (offline tile cache)")
            }
        }.getOrNull() ?: return get(url)
        runCatching {
            if (conn.responseCode == 200) {
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.isNotEmpty()) put(url, bytes)
            }
        }
        conn.disconnect()
        return get(url)
    }
    /** 预载一批瓦片（应放在 IO 线程调用）；返回成功张数 */
    @Synchronized
    fun preload(urls: List<String>, onProgress: ((done: Int, total: Int) -> Unit)? = null): Int {
        var ok = 0
        urls.forEachIndexed { i, u ->
            if (download(u) != null) ok += 1
            onProgress?.invoke(i + 1, urls.size)
        }
        return ok
    }

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun trim() {
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


