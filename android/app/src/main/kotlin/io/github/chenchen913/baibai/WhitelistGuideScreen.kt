package io.github.chenchen913.baibai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/** 省电白名单引导（四层防杀之第三层：国产 ROM 决定性动作；首次开始拜年前展示一次） */
@Composable
fun WhitelistGuideScreen(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BaibaiBg)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("🔒 锁屏记录前，先做这一步", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = BaibaiInk)
        Spacer(Modifier.height(6.dp))
        Text(
            "安卓系统为了省电，可能在锁屏后杀掉后台记录。把「拜拜」加入省电白名单后，锁屏揣兜里也能持续记录。",
            fontSize = 13.sp,
            color = BaibaiInk.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(14.dp))

        val rows = listOf(
            "小米/红米" to "设置 → 应用设置 → 拜拜 → 省电策略 → 无限制；并开启「自启动」",
            "华为/荣耀" to "设置 → 应用 → 应用启动管理 → 拜拜 → 手动管理 → 允许自启动/关联启动/后台活动",
            "OPPO/一加" to "设置 → 电池 → 更多设置 → 拜拜 → 允许后台运行；应用管理里开自启动",
            "vivo/iQOO" to "i管家 → 电池 → 后台耗电管理 → 拜拜 → 允许后台高耗电",
            "三星" to "设置 → 电池 → 后台使用限制 → 拜拜 → 不休眠",
        )
        rows.forEach { (brand, path) ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xAAFFFFFF)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(brand, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = BaibaiAccent)
                    Text(path, fontSize = 12.sp, color = BaibaiInk.copy(alpha = 0.75f), modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BaibaiAccent),
        ) {
            Text("我已完成设置，开始拜年", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}
