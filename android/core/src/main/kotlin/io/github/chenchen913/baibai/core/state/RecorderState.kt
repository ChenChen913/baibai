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

    companion object {
        private val seq = AtomicInteger(0)

        fun newId(prefix: String): String =
            "${prefix}_${System.currentTimeMillis().toString(36)}_${seq.incrementAndGet().toString(36)}"

        /** 新建空会话（IDLE）；date=用户选定的拜年日期（默认今天） */
        fun fresh(date: LocalDate = LocalDate.now()): RecorderState {
            val nowMs = System.currentTimeMillis()
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
                createdAt = nowMs,
                updatedAt = nowMs,
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
     * R8 三道入口闸门（真机主诉第二轮：静止 1~2 分钟仍拉出小段偏移轨迹；与网页版 state.ts 语义逐位一致）。
     * 根因：R7 固定 5m 门槛小于真实静止抖动幅度（室内散布直径 10~40m）——
     * 振荡抖动一旦越过 5m 就入库，之后基准跟着抖动走，慢慢拉出小圈。
     *
     * ① 跳变丢弃（R7 原样）：距上一入库点 2s 内 >100m（人力不可达，必为 GPS 坏点）
     *    直接丢弃且不进平滑窗口（防污染中位数），返回值带 jump 标记；
     * ② 中位数平滑（R8 新增）：原始 fix 进入滑动窗口（SMOOTH_WINDOW 个），
     *    入库候选 = 窗口各分量中位数（样本 ≥3 才可信，不足用原始值）——
     *    振荡抖动的中位数恒在抖动团中心，天然不长线；单点坏值被窗口吸收；
     * ③ 静止过滤（R8 精度自适应）：门槛 = min(max(5m, acc), 30m)——
     *    GPS 报多少米精度，就要求稳定估计至少走够多少米才入库，
     *    坐在室内（acc 15~40m）时门槛抬到 15~30m，抖动全滤；acc 上限 30m
     *    保证真实走动最差也每 ~30m 留一个点，轨迹形状不丢。
     * ④ 入库后窗口重置（R8 新增）：新入库点成为下一轮平滑锚点，防止旧抖动残留拖慢起步。
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
            // ③ 精度自适应静止门槛
            val thr = min(max(Constants.MIN_MOVE_M, acc), Constants.MOVE_THR_MAX_M)
            if (Geo.haversineM(prev.pos, cand) < thr) {
                return p // 稳定估计未走出门槛 → 不入库
            }
        }
        points += p
        smoothBuf.clear() // ④ 入库后窗口重置
        smoothBuf += cand
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
