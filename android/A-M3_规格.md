# A-M3 优化与压轴 · 规格（Android 版）

> 对应 `android/SPEC.md` §7。**经用户确认后**才进入"测试先行 → 实现"（按弹性规则以推荐默认值先行）。
> 前置依赖：A-M2 代码完成（85 项测试全绿）。

## 1. 范围

**做（本轮 core）：**
- `Tsp.kt`：Held-Karp 精确解（n≤16）+ 贪心/2-opt 启发式（n>16）；
- `Optimize.kt`：三线优化（飞行精确 / 距离×1.3 估算 / 时间按速度折算，同对取最短 D15）+ scorecard；
- `Polyline.kt`：路线闭环折线 / 弧长重采样 / 折线插值（压轴 morph 数学）；
- **昌乐县模拟村 15/20 户仿真**移植（数值口径与网页版一致，作为回归护栏）。

**做（下一轮 app）：** 三线对比页（Canvas 三线 + 推演动画 + **压轴 morph** + 成绩单四卡）、历史列表补绕路率。

**不做：** 清单/漏访/套名（A-M4）、高德地图与视觉精修（A-M5）。

## 2. 直译要点

| 网页模块 | Kotlin 目标 | 要点 |
|---|---|---|
| `tsp.ts` | `core/tsp/Tsp.kt` | `solveTsp(Array<DoubleArray>, start)`：n≤16 精确、n>16 贪心+2-opt（标记 exact=false）；EXACT_MAX=16 |
| `optimize.ts` | `core/optimize/Optimize.kt` | 常量 1.3 / 1.35 / 4.0；无向对聚合取 min（D15）；`RouteMode` 枚举 FLY/WALK_DIST/WALK_TIME；scorecard 口径=路上时间不含停留 |
| `polyline.ts` | `core/polyline/Polyline.kt` | 复用 Track 的 `XY`；重采样退化折线 = 重复点 |
| `tests/village-sim.test.ts` | `VillageSimTest.kt` | **同一 LCG（1664525/1013904223）+ 高斯抖动 + 蛇形绕路**；断言不变量 + 打印报告与网页版对照（浮点函数 V8/JVM 末位可能不同，数值用容差比对） |

## 3. app 层（下一轮，本规格先定界面）

- 回顾页顶部「三线对比 ✨」按钮 → 三线对比页；
- 三线对比页：成绩单四卡（今年实走/时间最优/距离最优/如果能飞）+ 标签切换三线 + Canvas 逐段点亮推演（0.35s/段）+ 未知段虚线 + **压轴按钮：实走轨迹 3 秒缓动 morph 成金色飞行星图**（180 点重采样、easeInOutCubic）；
- 历史列表补绕路率（依赖本里程碑 scorecard）。

## 4. 测试对照（本轮 19 项，见 `A-M3_测试对照表.md`）

tsp 4 + optimize 6 + polyline 7 + village-sim 2 = **19 项**，全部数值与网页版对照。

## 5. 验收

- 云端：全量测试绿（85 + 19 = 104 项）+ APK；
- 数值：昌乐县仿真报告与网页版结果对照（容差内一致）；
- 真机：压轴 morph 动画流畅（180 点 Canvas）。

## 6. 决策点（默认值先行，可逆）

1. 三线对比入口 = 回顾页顶部按钮（同网页版）；
2. morph 参数：180 点、3 秒、easeInOutCubic；
3. 成绩单四卡沿用网页版布局。
