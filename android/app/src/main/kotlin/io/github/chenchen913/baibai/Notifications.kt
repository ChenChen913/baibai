package io.github.chenchen913.baibai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.model.SessionState

/** 前台服务通知（四层防杀之第一层：常驻通知不可滑动删除） */
object Notifications {
    const val CHANNEL_ID = "baibai_recording"
    const val NOTIF_ID = 1

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "拜年记录", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "拜年轨迹记录进行中"
                    setShowBadge(false)
                },
            )
        }
    }

    fun build(context: Context, session: SessionData?): Notification {
        ensureChannel(context)
        // P9：已拜访户数 = 唯一户数（nodes 不含 home）
        val text = when (session?.state) {
            SessionState.WALKING -> "记录中 · 已拜访 ${session.nodes.size} 户"
            SessionState.PAUSED -> "在某户 · 已拜访 ${session.nodes.size} 户"
            else -> "拜年记录服务运行中"
        }
        val pi = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("拜拜 · 拜年记录")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}
