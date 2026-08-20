package io.github.chenchen913.baibai.core.state

import io.github.chenchen913.baibai.core.geo.Geo
import io.github.chenchen913.baibai.core.model.Action
import io.github.chenchen913.baibai.core.model.Checkpoint
import io.github.chenchen913.baibai.core.model.Constants
import io.github.chenchen913.baibai.core.model.Fix
import io.github.chenchen913.baibai.core.model.HouseNode
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.Mode
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.model.SessionState
import io.github.chenchen913.baibai.core.model.TrackPoint
import io.github.chenchen913.baibai.core.model.Visit
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/** 结束结果（对应网页版 {ok:true} | {ok:false, distM}） */
sealed interface FinishResult {
    data object Ok : FinishResult

    data class TooFar(val distM: Double) : FinishResult
}

/**
 * 会话状态机（对应网页版 state.ts，语义逐位一致）。
 * 线程安全：全部入口 @Synchronized——定位回调线程与 UI 线程并发（对照网页版 _infer_lock 教训）。
 */
class RecorderState private constructor(
    private val id: String,
    private val year: Int,
    private val date: String,
    private var home: LatLng,
    nodesInit: List<HouseNode>,
    visitsInit: List<Visit>,
    pointsInit: List<TrackPoint>,
    private var state: SessionState,
    private var currentMode: Mode,
    private var finished: Boolean,
    private val createdAt: Long,
    private var updatedAt: Long,
    private val actions: MutableList<Action>,
    private var segCounter: Int,
) {
    private val nodes = nodesInit.toMutableList()
    private val visits = visitsInit.toMutableList()
    private val points = pointsInit.toMutableList()

    /** R8：中位数平滑窗口（运行时态，不入快照/检查点——崩溃恢复后从空窗重启，无碍） */
    private val smoothBuf = mutableListOf<LatLng>()

    /** R9：连续确认计数（运行时态，不入快照/检查点——与 smoothBuf 一同从 0 重启） */
    private var confirmCount = 0

    /** R9.1：本段（start/resume 起）行走起点时刻——静止门槛的"静止时长"只算段内，
     *  不把在户内停留的几分钟算进去（否则出门短走访 <50m 永远追不上门槛，整段零入库丢边） */
    private var segStartT = 0L

    companion object {
        private val seq = AtomicInteger(0)

        fun newId(prefix: String): String =
            "${prefix}_${System.currentTimeMillis().toString(36)}_${seq.incrementAndGet().toString(36)}"

        /** 新建空会话（IDLE）；date=用户选定的拜年日期（默认今天）。
         *  R9：createdAtMs=点击「开始」时刻（供 UI 用时从点击起算，含等定位阶段；默认=当前时刻） */
        fun fresh(date: LocalDate = LocalDate.now(), createdAtMs: Long = System.currentTimeMillis()): RecorderState {
            return RecorderState(
                id = newId("s"),
                year = date.year,
                date = date.toString(),
                home = LatLng(0.0, 0.0),
                nodesInit = emptyList(),
                visitsInit = emptyList(),
                pointsInit = emptyList(),
                state = SessionState.IDLE,
                currentMode = Mode.WALK,
                finished = false,
                createdAt = createdAtMs,
                updatedAt = createdAtMs,
                actions = mutableListOf(),
                segCounter = 0,
            )
        }

        /** 从检查点恢复（D22：含撤销历史与段计数） */
        fun restore(ck: Checkpoint): RecorderState {
            val s = ck.session
            return RecorderState(
                id = s.id,
                year = s.year,
                date = s.date,
                home = s.home,
                nodesInit = s.nodes,
                visitsInit = s.visits,
                pointsInit = s.points,
                state = s.state,
                currentMode = s.currentMode,
                finished = s.finished || s.state == SessionState.FINISHED,
                createdAt = s.createdAt,
                updatedAt = s.updatedAt,
                actions = ck.actions.toMutableList(),
                segCounter = ck.segCounter,
            )
        }
    }

    val currentState: SessionState
        @Synchronized get() = state

    /** 当前会话快照（不可变） */
    @Synchronized
    fun snapshot(): SessionData = SessionData(
        id = id,
        year = year,
        date = date,
        home = home,
        nodes = nodes.toList(),
        visits = visits.toList(),
        points = points.toList(),
        state = state,
        currentMode = currentMode,
        finished = finished,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    /** 检查点：会话 + 撤销历史 + 段计数 */
    @Synchronized
    fun checkpoint(): Checkpoint = Checkpoint(snapshot(), actions.toList(), segCounter)

    /** IDLE → WALKING：确定 Home（中位数，允许 fallback） */
    @Synchronized
    fun start(homeFixes: List<Fix>, now: Long, fallback: Fix? = null) {
        if (state != SessionState.IDLE) {
            throw IllegalStateException("非法转移：仅待机状态可开始")
        }
        val good = homeFixes.filter { it.acc <= Constants.GOOD_ACC_M }
        val base = if (good.isNotEmpty()) good else homeFixes
        var homePos = Geo.medianPos(base.map { it.pos })
        if (homePos == null && fallback != null) homePos = fallback.pos
        if (homePos == null) throw IllegalArgumentException("无有效定位，无法确定 Home")
        home = homePos
        state = SessionState.WALKING
        finished = false
        smoothBuf.clear() // R8：新会话平滑窗口从空开始
        confirmCount = 0 // R9：确认链一并清零
        segStartT = now // R9.1：段起点
        actions += Action.Start
        touch(now)
    }

    /** WALKING → PAUSED：创建节点（≤10m 合并，D10）或合并到已有节点/Home；返回该户 */
    @Synchronized
    fun pause(fixes: List<Fix>, now: Long): HouseNode {
        if (state != SessionState.WALKING) {
            throw IllegalStateException("非法转移：仅移动中可暂停")
        }
        val good = fixes.filter { it.acc <= Constants.GOOD_ACC_M }
        var lowAcc = false
        var pos = Geo.medianPos(good.map { it.pos })
        if (pos == null && fixes.isNotEmpty()) {
            pos = fixes.last().pos // 全部低精度：用原始最后一点
            lowAcc = true
        }
        if (pos == null) throw IllegalArgumentException("无有效定位，无法确定节点位置")

        val nearest = Geo.nearest(pos, allLocated())
        val n = nearest.node
        val node: HouseNode
        val nodeId: String
        var created = false
        if (n != null && nearest.distM <= Constants.MERGE_THRESHOLD_M) {
            nodeId = n.id
            node = if (n.id == Constants.HOME_ID) {
                HouseNode(Constants.HOME_ID, "Home", 0, home)
            } else {
                nodes.first { it.id == n.id }
            }
        } else {
            nodeId = newId("n")
            node = HouseNode(
                id = nodeId,
                name = "",
                autoNo = nodes.size + 1,
                pos = pos,
                lowAcc = if (lowAcc) true else null,
            )
            nodes += node
            created = true
        }

        visits += Visit(nodeId = nodeId, arriveT = now, leaveT = null, mode = currentMode)
        state = SessionState.PAUSED
        actions += Action.Pause(nodeId = nodeId, created = created, mode = currentMode)
        currentMode = Mode.WALK // D19：到下一户自动回走路
        touch(now)
        return node
    }

    /** PAUSED → WALKING：离开该户，段 id 自增 */
    @Synchronized
    fun resume(now: Long) {
        if (state != SessionState.PAUSED) {
            throw IllegalStateException("非法转移：仅在某户可继续")
        }
        val last = visits.last()
        visits[visits.size - 1] = last.copy(leaveT = now)
        segCounter += 1
        state = SessionState.WALKING
        smoothBuf.clear() // R8：新段平滑窗口从空开始（上一段的旧 fix 不拖慢本段起步）
        confirmCount = 0 // R9：确认链一并清零
        segStartT = now // R9.1：新段起点——户内停留时长不计入静止门槛
        actions += Action.Resume
        touch(now)
    }

    /** → FINISHED：到家结束（WALKING/PAUSED 均可，SPEC v1.1 修正）；距 Home >10m 需 force */
    @Synchronized
    fun finish(fixes: List<Fix>, now: Long, force: Boolean = false): FinishResult {
        if (state != SessionState.WALKING && state != SessionState.PAUSED) {
            throw IllegalStateException("非法转移：仅移动中或某户可结束")
        }
        val good = fixes.filter { it.acc <= Constants.GOOD_ACC_M }
        val pos = Geo.medianPos(good.map { it.pos }) ?: fixes.lastOrNull()?.pos
        val distM = pos?.let { Geo.haversineM(it, home) } ?: Double.POSITIVE_INFINITY
        if (pos != null && distM <= Constants.FINISH_OK_M) {
            finalize(now)
            return FinishResult.Ok
        }
        if (force) {
            finalize(now)
            return FinishResult.Ok
        }
        return FinishResult.TooFar(distM)
    }

    private fun finalize(now: Long) {
        val prev = state
        state = SessionState.FINISHED
        finished = true
        actions += Action.Finish(prev = prev)
        touch(now)
    }

    /**
     * 记录轨迹点（仅 WALKING）。
     * R8 三道入口闸门 + R9 三道补强（真机第三轮：漂移"一阵一阵"概率性出现；与网页版 state.ts 语义逐位一致）。
     * R8 根因：中位数只能挡"振荡抖动"，挡不住"单向慢漂移"（多路径效应下单向游走，
     * 中位数跟着走）；且窗口未满 3 个样本时候选=原始抖动点；国产 ROM acc 虚标 5m 时门槛过低。
     *
     * ① 跳变丢弃（R7 原样）：距上一入库点 2s 内 >100m（人力不可达，必为 GPS 坏点）
     *    直接丢弃且不进平滑窗口（防污染中位数），返回值带 jump 标记；
     * ② 中位数平滑（R8 原样）：原始 fix 进入滑动窗口（SMOOTH_WINDOW 个），
     *    入库候选 = 窗口各分量中位数（样本 ≥3 才可信，不足用原始值）；
     * ③ 窗口未满不入库（R9 新增）：首点后窗口攒满 3 个样本前一律不入库——
     *    挡住"开始初期第 2 个原始抖动点直入"的漏洞（起步记录最多延迟 ~3 秒，无碍）；
     * ④ 静止门槛随段内静止时长抬升（R9 新增，R9.1 修正起算点）：
     *    thr = min(max(5m, acc), 30m) + 段内静止秒数 × 0.5m/s，封顶 50m——
     *    单向慢漂移（<0.5m/s）永远追不上门槛，彻底不长线；
     *    静止时长从 max(上一入库点, 段起点) 起算：段起点 = start/resume 时刻，
     *    在户内停留的几分钟不算静止——否则出门走访邻户（<50m）整段零入库、回顾页丢边；
     * ⑤ 连续确认（R9 新增）：连续 MOVE_CONFIRM_N 个平滑候选都超门槛才入库——
     *    "一阵"短暂漂移超门槛一两秒后回落，确认链断裂被挡；真走动持续超门槛，确认链成立；
     * ⑥ 入库后窗口重置 + 确认清零（R8 原样扩展）：新入库点成为下一轮平滑锚点。
     */
    @Synchronized
    fun addPoint(pos: LatLng, acc: Double, now: Long): TrackPoint {
        if (state != SessionState.WALKING) {
            throw IllegalStateException("非法转移：仅移动中记录轨迹点")
        }
        val seg = "seg$segCounter"
        val prev = points.lastOrNull()
        // ① 跳变点：不入库、不进平滑窗口
        if (prev != null &&
            now - prev.t < Constants.JUMP_DT_MS &&
            Geo.haversineM(prev.pos, pos) > Constants.JUMP_DIST_M
        ) {
            return TrackPoint(t = now, pos = pos, acc = acc, seg = seg, jump = true)
        }
        // ② 原始 fix 进滑动窗口，取中位数为入库候选
        smoothBuf += pos
        if (smoothBuf.size > Constants.SMOOTH_WINDOW) smoothBuf.removeAt(0)
        val cand = if (smoothBuf.size >= 3) Geo.medianPos(smoothBuf)!! else pos
        val p = TrackPoint(t = now, pos = cand, acc = acc, seg = seg)
        if (prev != null) {
            // ③ 窗口未满 3：不判定（攒样本）——防开始初期原始抖动点直入
            if (smoothBuf.size < 3) return p
            // ④ 静止门槛 = 精度自适应基线 + 段内静止时长增速，封顶 50m
            //    R9.1：静止时长从 max(上一入库点, 段起点) 起算——在户内停留的几分钟不算静止，
            //    否则出门后走访隔壁邻户（<50m）永远追不上门槛，整段零入库、回顾页丢边
            val base = min(max(Constants.MIN_MOVE_M, acc), Constants.MOVE_THR_MAX_M)
            val idleSec = (now - max(prev.t, segStartT)) / 1000.0
            val thr = min(base + idleSec * Constants.IDLE_GROW_M_PER_S, Constants.MOVE_THR_IDLE_CAP_M)
            val dist = Geo.haversineM(prev.pos, cand)
            // ⑤ 连续确认：连续 MOVE_CONFIRM_N 个候选都超门槛才入库
            if (dist < thr) {
                confirmCount = 0 // 回落 → 确认链断裂
                return p
            }
            confirmCount += 1
            if (confirmCount < Constants.MOVE_CONFIRM_N) return p
        }
        points += p
        smoothBuf.clear() // ⑥ 入库后窗口重置
        smoothBuf += cand
        confirmCount = 0
        return p
    }

    /** 出行方式切换（D19：默认走路，到户自动回走路） */
    @Synchronized
    fun setMode(mode: Mode, now: Long) {
        if (state == SessionState.FINISHED) return
        currentMode = mode
        touch(now)
    }

    /** 撤销最近一次操作（LIFO；D19 R2）；无可撤销返回 false */
    @Synchronized
    fun undo(): Boolean {
        val a = actions.removeLastOrNull() ?: return false
        when (a) {
            is Action.Start -> {
                // 撤销开始：回到待机并清空本次数据
                nodes.clear()
                visits.clear()
                points.clear()
                state = SessionState.IDLE
                segCounter = 0
                smoothBuf.clear() // R8：平滑窗口一并清空
                confirmCount = 0 // R9：确认链一并清零
                segStartT = 0 // R9.1：段起点一并复位
            }

            is Action.Pause -> {
                visits.removeLast()
                if (a.created) {
                    val i = nodes.indexOfFirst { it.id == a.nodeId }
                    if (i >= 0) nodes.removeAt(i)
                }
                currentMode = a.mode // 还原暂停前的出行方式
                state = SessionState.WALKING
            }

            is Action.Resume -> {
                val last = visits.lastOrNull()
                if (last != null) visits[visits.size - 1] = last.copy(leaveT = null)
                segCounter -= 1
                state = SessionState.PAUSED
            }

            is Action.Finish -> {
                state = a.prev
                finished = false
            }
        }
        return true
    }

    /** 所有可合并目标：Home + 全部节点 */
    private fun allLocated(): List<Geo.Located> =
        listOf(Geo.Located(Constants.HOME_ID, home)) +
            nodes.map { Geo.Located(it.id, it.pos) }

    private fun touch(now: Long) {
        updatedAt = now
    }
}
