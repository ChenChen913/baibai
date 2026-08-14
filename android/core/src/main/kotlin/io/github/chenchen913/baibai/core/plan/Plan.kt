package io.github.chenchen913.baibai.core.plan

import io.github.chenchen913.baibai.core.geo.Geo
import io.github.chenchen913.baibai.core.model.HouseNode
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.Plan
import io.github.chenchen913.baibai.core.model.PlanItem
import io.github.chenchen913.baibai.core.model.SessionData

/** 跨年便利纯函数（对应网页版 plan.ts：清单生成/漏访匹配/套名候选，D17/D18/F-9） */

const val PLAN_MATCH_M = 10.0 // 与 D10 节点合并阈值一致

data class VisitedMatch(val item: PlanItem, val nodeId: String)

data class MatchResult(val visited: List<VisitedMatch>, val missing: List<PlanItem>)

object PlanOps {

    /** 从往年会话生成今年清单（F-1） */
    fun planFromSession(prev: SessionData, year: Int, now: Long = System.currentTimeMillis()): Plan =
        Plan(
            year = year,
            items = prev.nodes.map { PlanItem(name = it.name, pos = it.pos) },
            createdAt = now,
            updatedAt = now,
        )

    /** 贪心一对一匹配：item×node 距离升序，≤threshold 配对，每方最多一次（D18 回顾页对比） */
    fun matchPlan(s: SessionData, plan: Plan, threshold: Double = PLAN_MATCH_M): MatchResult {
        data class Cand(val i: Int, val j: Int, val d: Double)
        val cands = mutableListOf<Cand>()
        plan.items.forEachIndexed { i, item ->
            val pos = item.pos ?: return@forEachIndexed // 无坐标项不参与自动匹配
            s.nodes.forEachIndexed { j, n ->
                cands.add(Cand(i, j, Geo.haversineM(pos, n.pos)))
            }
        }
        cands.sortBy { it.d }
        val usedItem = mutableSetOf<Int>()
        val usedNode = mutableSetOf<Int>()
        val visited = mutableListOf<VisitedMatch>()
        for (c in cands) {
            if (c.d > threshold) break
            if (c.i in usedItem || c.j in usedNode) continue
            usedItem.add(c.i)
            usedNode.add(c.j)
            visited.add(VisitedMatch(plan.items[c.i], s.nodes[c.j].id))
        }
        val missing = plan.items.filterIndexed { i, _ -> i !in usedItem }
        return MatchResult(visited, missing)
    }

    data class NameCandidate(val name: String, val distM: Double)

    /** 套名候选：去年**有名字**的户按距离升序取前 top 个（D17） */
    fun nameCandidates(nodePos: LatLng, prevNodes: List<HouseNode>, top: Int = 3): List<NameCandidate> =
        prevNodes
            .filter { it.name.isNotBlank() }
            .map { NameCandidate(it.name, Geo.haversineM(nodePos, it.pos)) }
            .sortedBy { it.distM }
            .take(top)
}
