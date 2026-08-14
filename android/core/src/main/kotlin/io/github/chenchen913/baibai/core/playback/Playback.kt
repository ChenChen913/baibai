package io.github.chenchen913.baibai.core.playback

import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.smooth.Smooth
import io.github.chenchen913.baibai.core.track.Track
import io.github.chenchen913.baibai.core.track.XY
import kotlin.math.max
import kotlin.math.min

data class PlaybackPoint(val x: Double, val y: Double, val t: Long)

data class PlaybackPlan(val pts: List<PlaybackPoint>, val totalMs: Long)

/** 回放引擎（对应网页版 playback.ts，语义逐位一致） */
object Playback {

    /** 会话 → 回放计划：各边平滑点合并 → DP 抽稀（保留时间轴）→ 投影视口 */
    fun buildPlan(s: SessionData, w: Double, h: Double): PlaybackPlan {
        val raw = mutableListOf<Pair<LatLng, Long>>()
        for (e in Track.buildEdges(s)) {
            for (p in e.smoothed) raw.add(p.pos to p.t)
        }
        if (raw.isEmpty()) return PlaybackPlan(emptyList(), 0)
        val keep = Smooth.douglasPeuckerKeep(raw.map { it.first }, 2.0)
        val proj = Track.projectToView(keep.map { raw[it].first }, w, h)
        val pts = keep.mapIndexed { k, i -> PlaybackPoint(proj[k].x, proj[k].y, raw[i].second) }
        return PlaybackPlan(pts, max(0, pts.last().t - pts.first().t))
    }

    /** 播放进度 ms → 光点位置（沿时间轴线性插值，越界夹到端点） */
    fun positionAt(plan: PlaybackPlan, ms: Long): XY? {
        val pts = plan.pts
        if (pts.isEmpty()) return null
        val target = pts[0].t + ms
        if (target <= pts[0].t) return XY(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) {
            if (target <= pts[i].t) {
                val a = pts[i - 1]
                val b = pts[i]
                val dt = b.t - a.t
                val f = if (dt > 0) (target - a.t).toDouble() / dt else 0.0
                return XY(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
            }
        }
        val last = pts.last()
        return XY(last.x, last.y)
    }

    fun fractionAt(plan: PlaybackPlan, ms: Long): Double {
        if (plan.totalMs <= 0) return 0.0
        return min(1.0, max(0.0, ms.toDouble() / plan.totalMs))
    }
}
