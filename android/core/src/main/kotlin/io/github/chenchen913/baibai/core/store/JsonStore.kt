package io.github.chenchen913.baibai.core.store

import io.github.chenchen913.baibai.core.model.Checkpoint
import io.github.chenchen913.baibai.core.model.ExportFile
import io.github.chenchen913.baibai.core.model.Plan
import io.github.chenchen913.baibai.core.model.SessionData
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * JSON 文件存储（对应网页版 db.ts）。
 * 目录注入（filesDir 或测试 @TempDir），无 Android 依赖；
 * 原子写：tmp → fsync → rename；格式 = android/数据格式.md。
 */
class JsonStore(
    private val dir: File,
    private val json: Json = defaultJson(),
) {
    companion object {
        const val CHECKPOINT_FILE = "checkpoint.json"
        const val SESSIONS_DIR = "sessions"
        const val PLANS_DIR = "plans"

        fun defaultJson(): Json = Json {
            explicitNulls = false // 契约要求可选字段缺省（不输出 "lowAcc": null）
            encodeDefaults = false
            ignoreUnknownKeys = true
        }
    }

    /** 保存活跃会话检查点（整体覆盖写，D22） */
    fun saveActive(ck: Checkpoint) {
        atomicWrite(File(dir, CHECKPOINT_FILE), json.encodeToString(Checkpoint.serializer(), ck))
    }

    fun loadActive(): Checkpoint? {
        val f = File(dir, CHECKPOINT_FILE)
        if (!f.exists()) return null
        return json.decodeFromString(Checkpoint.serializer(), f.readText())
    }

    fun clearActive() {
        File(dir, CHECKPOINT_FILE).delete()
    }

    /** 结束后的历史会话 */
    fun saveSession(s: SessionData) {
        val f = File(dir, "$SESSIONS_DIR/${s.id}.json")
        atomicWrite(f, json.encodeToString(SessionData.serializer(), s))
    }

    fun listSessions(): List<SessionData> {
        val d = File(dir, SESSIONS_DIR)
        if (!d.exists()) return emptyList()
        return d.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                runCatching { json.decodeFromString(SessionData.serializer(), f.readText()) }
                    .getOrNull()
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    /** 今年清单（M4，对应网页版 plans store） */
    fun savePlan(p: Plan) {
        atomicWrite(File(dir, "$PLANS_DIR/${p.year}.json"), json.encodeToString(Plan.serializer(), p))
    }

    fun loadPlan(year: Int): Plan? {
        val f = File(dir, "$PLANS_DIR/$year.json")
        if (!f.exists()) return null
        return json.decodeFromString(Plan.serializer(), f.readText())
    }

    fun listPlans(): List<Plan> {
        val d = File(dir, PLANS_DIR)
        if (!d.exists()) return emptyList()
        return d.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                runCatching { json.decodeFromString(Plan.serializer(), f.readText()) }
                    .getOrNull()
            }
            ?: emptyList()
    }

    fun clearPlan(year: Int) {
        File(dir, "$PLANS_DIR/$year.json").delete()
    }

    /** 全量导出（换机备份 / 与网页版互通）：sessions + plans（清单） */
    fun exportAllJson(): String {
        val sessions = listSessions()
        val export = ExportFile(
            app = "baibai",
            version = 1,
            exportedAt = java.time.Instant.now().toString(),
            sessions = sessions,
            plans = listPlans(),
        )
        return json.encodeToString(ExportFile.serializer(), export)
    }

    private val safeId = Regex("^[A-Za-z0-9_-]+$")

    /** 全量导入（网页版导出文件 → 本机会话库）；契约 §1：app/version 不符拒绝并提示；id 校验防路径穿越；返回导入会话数 */
    fun importAllJson(text: String): Int {
        val export = json.decodeFromString(ExportFile.serializer(), text)
        if (export.app != "baibai") {
            throw IllegalArgumentException("这不是拜拜的备份文件，无法导入")
        }
        if (export.version != 1) {
            throw IllegalArgumentException("备份版本不兼容，请先升级应用后再导入")
        }
        export.sessions.forEach { s ->
            if (!safeId.matches(s.id)) {
                throw IllegalArgumentException("备份中存在不合法的会话 id：${s.id}，已拒绝导入")
            }
            saveSession(s)
        }
        export.plans.forEach { savePlan(it) }
        return export.sessions.size
    }

    private fun atomicWrite(f: File, content: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(content)
        // 注意：append=true 关键——默认构造会截断文件（清空刚写的内容），这里只为 fsync
        FileOutputStream(tmp, true).use { it.fd.sync() }
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) {
                throw IOException("原子写失败: ${f.name}")
            }
        }
    }
}
