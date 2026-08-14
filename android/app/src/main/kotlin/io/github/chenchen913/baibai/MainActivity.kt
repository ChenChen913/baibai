package io.github.chenchen913.baibai

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** A-M1 占位入口：后续里程碑替换为 Compose 驾驶舱 + 前台服务接线 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "拜拜 · A-M1 开发中（记录闭环）"
            textSize = 18f
        })
    }
}
