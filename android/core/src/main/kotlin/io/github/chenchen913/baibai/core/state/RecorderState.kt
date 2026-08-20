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
     * R7 两道入口闸门（真机主诉：人坐着不动，回放却拉出复杂轨迹；与网页版 state.ts 语义逐位一致）：
     * ① 跳变丢弃：2s 内位移 >100m（180km/h，人力不可达，必为 GPS 坏点）不入库，
     *    返回值带 jump 标记供调用方感知——回顾页「跳变点列表」因此恒空，属预期；
     * ② 静止过滤：距上一入库点 <5m（GPS 静止抖动 3~10m）不入库——人不动就不长轨迹。
     * 注意过滤基准是「上一入库点」而非上一个 fix：连续小幅抖动累计不破闸。
     */
    @Synchronized
    fun addPoint(pos: LatLng, acc: Double, now: Long): TrackPoint {
        if (state != SessionState.WALKING) {
            throw IllegalStateException("非法转移：仅移动中记录轨迹点")
        }
        val p = TrackPoint(t = now, pos = pos, acc = acc, seg = "seg$segCounter")
        val prev = points.lastOrNull()
        if (prev != null) {
            val d = Geo.haversineM(prev.pos, pos)
            if (now - prev.t < Constants.JUMP_DT_MS && d > Constants.JUMP_DIST_M) {
                return p.copy(jump = true) // ① 跳变点直接丢弃（不入库）
            }
            if (d < Constants.MIN_MOVE_M) {
                return p // ② 静止抖动不入库
            }
        }
        points += p
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
