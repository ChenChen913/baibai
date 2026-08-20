package io.github.chenchen913.baibai.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 契约常量（android/数据格式.md §9，禁止漂移） */
object Constants {
    const val HOME_ID = "home"
    const val MERGE_THRESHOLD_M = 10.0
    const val FINISH_OK_M = 20.0 // 结束拜年自动判定半径：GPS 民码误差 ±3~10m，到家门口不烦用户
    const val GOOD_ACC_M = 50.0
    const val JUMP_DIST_M = 100.0
    const val JUMP_DT_MS = 2000L
    const val MIN_MOVE_M = 5.0 // R7/R8：静止位移门槛基线——GPS 报多少米精度，就至少走够多少米才入库
    const val MOVE_THR_MAX_M = 30.0 // R8：精度自适应门槛上限——acc 再差也保证真实走动每 ~30m 留一个点
    const val SMOOTH_WINDOW = 5 // R8：中位数平滑窗口大小——吸收振荡抖动与单点坏值
    const val MIN_VIEW_SPAN_M = 60.0 // R7：回放/回顾投影最小跨度——静止小点团不得被放大充满视口
    const val R = 6371000.0
}

@Serializable
data class LatLng(val lat: Double, val lng: Double)

@Serializable
data class Fix(val pos: LatLng, val acc: Double, val src: String? = null) // src："gps"/"net"（网关坐标系隔离用；null=未知/测试）

@Serializable
enum class Mode {
    @SerialName("walk")
    WALK,

    @SerialName("bike")
    BIKE,
}

@Serializable
enum class SessionState {
    IDLE,
    WALKING,
    PAUSED,
    FINISHED,
}

@Serializable
data class TrackPoint(
    val t: Long,
    val pos: LatLng,
    val acc: Double,
    val seg: String,
    val jump: Boolean? = null,
)

@Serializable
data class Visit(
    val nodeId: String,
    val arriveT: Long,
    val leaveT: Long? = null,
    val mode: Mode,
)

@Serializable
data class HouseNode(
    val id: String,
    val name: String,
    val autoNo: Int,
    val pos: LatLng,
    val lowAcc: Boolean? = null,
)

@Serializable
data class SessionData(
    val id: String,
    val year: Int,
    val date: String,
    val home: LatLng,
    val nodes: List<HouseNode>,
    val visits: List<Visit>,
    val points: List<TrackPoint>,
    val state: SessionState,
    val currentMode: Mode,
    val finished: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 撤销历史（LIFO；JSON class discriminator = "type"，类名经 @SerialName 对齐契约小写值） */
@Serializable
sealed interface Action {
    @Serializable
    @SerialName("start")
    data object Start : Action

    @Serializable
    @SerialName("pause")
    data class Pause(val nodeId: String, val created: Boolean, val mode: Mode) : Action

    @Serializable
    @SerialName("resume")
    data object Resume : Action

    @Serializable
    @SerialName("finish")
    data class Finish(val prev: SessionState) : Action
}

@Serializable
data class Checkpoint(
    val session: SessionData,
    val actions: List<Action>,
    val segCounter: Int,
)

@Serializable
data class PlanItem(val name: String, val pos: LatLng? = null)

@Serializable
data class Plan(
    val year: Int,
    val items: List<PlanItem>,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class ExportFile(
    val app: String,
    val version: Int,
    val exportedAt: String,
    val sessions: List<SessionData>,
    val plans: List<Plan> = emptyList(),
)
