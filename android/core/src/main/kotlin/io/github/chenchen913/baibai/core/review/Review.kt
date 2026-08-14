package io.github.chenchen913.baibai.core.review

import io.github.chenchen913.baibai.core.model.Constants
import io.github.chenchen913.baibai.core.model.HouseNode
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.state.RecorderState

/** 回顾页收拾操作（对应网页版 review.ts，纯函数，语义逐位一致） */
object Review {

    /** 改名（home 不可改名） */
    fun renameNode(s: SessionData, nodeId: String, name: String): SessionData {
        if (nodeId == Constants.HOME_ID) return s
        return s.copy(nodes = s.nodes.map { if (it.id == nodeId) it.copy(name = name) else it })
    }

    /** 合并两户：drop 的访问并入 keep，keep 名优先（空则继承 drop 名） */
    fun mergeNodes(s: SessionData, keepId: String, dropId: String): SessionData {
        val keep = s.nodes.find { it.id == keepId } ?: return s
        val drop = s.nodes.find { it.id == dropId } ?: return s
        if (keepId == dropId) return s
        val merged = s.copy(
            nodes = s.nodes
                .filter { it.id != dropId }
                .map { n -> if (n.id == keepId) n.copy(name = n.name.ifEmpty { drop.name }) else n },
            visits = s.visits.map { v -> if (v.nodeId == dropId) v.copy(nodeId = keepId) else v },
        )
        return renumberNodes(merged)
    }

    /** 拆分某次访问为独立新户：新户坐标 = arriveT 之前最近的一个轨迹点 */
    fun splitVisit(s: SessionData, visitIdx: Int): SessionData {
        val v = s.visits.getOrNull(visitIdx) ?: return s
        if (v.nodeId == Constants.HOME_ID) return s
        var bestT = Long.MIN_VALUE
        var bestPos: LatLng? = null
        var bestAcc = 99.0
        for (p in s.points) {
            if (p.t <= v.arriveT && p.t > bestT) {
                bestT = p.t
                bestPos = p.pos
                bestAcc = p.acc
            }
        }
        val pos = bestPos ?: return s
        val node = HouseNode(
            id = RecorderState.newId("n"),
            name = "",
            autoNo = s.nodes.size + 1,
            pos = pos,
            lowAcc = if (bestAcc > Constants.GOOD_ACC_M) true else null,
        )
        val splitted = s.copy(
            nodes = s.nodes + node,
            visits = s.visits.mapIndexed { i, vv -> if (i == visitIdx) vv.copy(nodeId = node.id) else vv },
        )
        return renumberNodes(splitted)
    }

    /** 剔除一个轨迹点（用于异常跳变点） */
    fun removePoint(s: SessionData, t: Long): SessionData =
        s.copy(points = s.points.filter { it.t != t })

    /** 按首次到访顺序重排 autoNo（合并/拆分后保持编号直观） */
    fun renumberNodes(s: SessionData): SessionData {
        val order = mutableListOf<String>()
        for (v in s.visits.sortedBy { it.arriveT }) {
            if (v.nodeId != Constants.HOME_ID && v.nodeId !in order) order.add(v.nodeId)
        }
        for (n in s.nodes) {
            if (n.id !in order) order.add(n.id)
        }
        val no = order.mapIndexed { i, id -> id to (i + 1) }.toMap()
        return s.copy(nodes = s.nodes.map { n -> n.copy(autoNo = no[n.id] ?: n.autoNo) })
    }
}
