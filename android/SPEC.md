# Android 原生版迁移 SPEC（v1）

> 状态：前置任务文档。目的：把网页版（PWA）完整迁移为 **Android 原生 App（Kotlin）**。
> 决策依据：用户硬要求——① 安卓手机运行；② **锁屏揣兜里持续记录**；③ 进程被杀后**绝不前功尽弃**。
> 前置文档索引：[数据格式](./数据格式.md) · [防杀与后台定位](./防杀与后台定位.md) · [开发环境与流程](./开发环境与流程.md)（**本地零安卓工具链方案 + 软件工程流程**）

## 1. 目标与非目标

**目标**
- Android 原生 App：锁屏/揣兜里后台持续定位记录，进程保活四层防线；
- 功能与网页版 1:1（记录 → 回放 → 收拾 → 三线优化 → 压轴动画 → 清单/漏访/套名 → 历年成绩单）；
- **数据互通**：安卓与网页版使用同一份 JSON 导出格式，双向导入；
- 算法层（状态机/TSP/平滑等）从 TypeScript **逐函数直译**，111 项测试全部移植为 JUnit，行为逐条对照。

**非目标（明确不做）**
- 不做 iOS 版（单平台用户，跨端溢价不付）；
- 不做应用商店上架（唯一用户，APK 侧载即可）；
- 不做账号/云同步（沿用"纯本地 + 手动导出备份"架构）；
- 不在本阶段接入 RTK（预留蓝牙串口接口，见 §8）。

## 2. 技术栈定稿（含一处对早期方案的重要修正）

| 层 | 选型 | 说明 |
|---|---|---|
| 语言 | **Kotlin** | 官方一等方式 |
| UI | **Jetpack Compose** | UI 自由度最高、动画生态好（"高级感"需求） |
| 地图 | **WebView + Leaflet + OpenStreetMap（2026-08-14 修正，免 Key）** | 与网页版同一套地图方案，免费免 Key、开箱即用；瓦片失败自动换源 + 纸底兜底。高德瓦片可作为可选升级（见 高德接入指引.md §4） |
| 定位 | **系统 LocationManager（SystemLocationSource）** | A-M1 决策：免 Key 跑通全链路；WGS-84 与 OSM 天然一致，无 GCJ-02 转换问题。高德定位 SDK 留作 LocationSource 抽象上的后续可选项 |
| 持久化 | **JSON 文件（filesDir）+ 原子写** | 与网页版 IndexedDB 的三份数据（会话/检查点/清单）格式一致，见数据格式文档；10s+按键检查点架构原样保留 |
| 后台 | **前台服务（foregroundServiceType="location"）** | 见防杀文档 |
| 算法 | TS 纯函数 → Kotlin 纯函数直译 | 对照基准 = 现有 111 项测试 |
| 构建/分发 | Gradle + APK 侧载 | minSdk 26 / targetSdk 34 |
| 测试 | JUnit（算法）+ Compose UI 测试（冒烟）+ 真机验收 | |

## 3. 架构总览

```
MainActivity (Compose 单 Activity)
   ├── RecordScreen / ReviewScreen / OptimizeScreen / PlanScreen / HistoryScreen
   ├── AppViewModel（状态机 + UI 状态，与网页版 main.ts 同职责）
   │        │
   │        ▼
   ├── RecorderState.kt（纯 Kotlin 状态机，无 Android 依赖）
   ├── LocationService.kt（前台服务：系统定位回调 → RecorderState.addPoint）
   └── JsonStore.kt（filesDir JSON：检查点/会话/清单，原子写）
```

- **线程模型**：定位回调在服务线程 → 加锁写入 RecorderState（对照网页版 `_infer_lock` 的教训：状态机必须线程安全）；UI 观察 ViewModel 的 StateFlow；
- **检查点**：每 10s + 每次按键（开始/暂停/继续/撤销/结束）原子写检查点；启动时发现未完成检查点 → 弹"继续 / 放弃"。

## 4. 四层防杀（你的硬要求，详见防杀文档）

1. 前台服务 + 常驻通知（系统第一优先级保护）；
2. START_STICKY 自重启 + 定位继续；
3. 引导加入省电白名单（小米/华为/OPPO/vivo 图文路径矩阵）；
4. 检查点续录兜底——**进程死了也不前功尽弃**。

## 5. 模块映射表（TS → Kotlin 一一对应，移植顺序即此表顺序）

| 网页版模块 | Android 模块 | 移植内容 |
|---|---|---|
| `src/geo.ts` | `Geo.kt` | haversine / 分量中位数 / 最近节点；`LatLng`/`Fix` data class |
| `src/state.ts` | `RecorderState.kt` | 状态机：开始/暂停/继续/撤销(LIFO)/结束、10m 合并、自动编号、跳变防护、检查点与恢复；**线程安全版本** |
| `src/smooth.ts` | `Smooth.kt` | 跳变切段 / 段内滑动平均（端点保持）/ Douglas-Peucker |
| `src/track.ts` | `Track.kt` | buildEdges（时间窗分段、方式归属）/ SVG 投影 → Canvas/Compose 绘制 |
| `src/tsp.ts` | `Tsp.kt` | Held-Karp（n≤16 精确）+ 贪心/2-opt |
| `src/optimize.ts` | `Optimize.kt` | 三线优化 + 边权聚合（D15）+ scorecard |
| `src/polyline.ts` | `Polyline.kt` | 弧长重采样 / 折线插值（压轴 morph） |
| `src/plan.ts` | `Plan.kt` | 清单生成 / 漏访一对一匹配 / 套名候选 |
| `src/review.ts` | `Review.kt` | 改名/合并/拆分/剔除异常点 |
| `src/playback.ts` | `Playback.kt` | 回放计划与插值 |
| `src/demo.ts` | `Demo.kt` | 演示数据生成器（UI 演示用 8 户；昌乐县 15/20 户仿真在 `VillageSimTest.kt` 测试中，L-6 修正） |
| `src/db.ts` | `JsonStore.kt` | 检查点/会话/清单 JSON 读写 + 全量导出导入 |
| `src/gps.ts` | `LocationService.kt` | 高德定位回调、最近 fix 环形缓冲、错误分类提示 |
| `src/ui.ts` 等 4 个视图 | Compose 五个 Screen + `UiTheme.kt` | 驾驶舱布局/回顾/三线/清单/历史 + 暖色年味主题 + SVG 图标 → Vector Drawable |
| `src/main.ts` | `MainActivity.kt` + `AppViewModel.kt` | 视图路由、看门狗、震动、亮屏、导出 |
| `public/sw.js` | —（不需要） | 原生无离线缓存问题 |

## 6. 测试策略

- **算法层**：把现有 111 项测试**逐条直译**成 JUnit（geo/state/smooth/track/tsp/optimize/polyline/plan/review/db/playback），含昌乐县 15/20 户仿真（结果数值与网页版对照一致才算过）；
- **UI 冒烟**：Compose UI Test 移植 jsdom 冒烟测试（每个按钮可点、回调触发、禁用状态）；
- **真机验收清单**（必须在用户手机上过）：
  1. 锁屏 10 分钟 → 解锁 → 轨迹连续（关键验收）；
  2. 屏幕关闭后继续走 → 到户暂停 → 正常；
  3. 手动杀掉 App → 重开 → "继续未完成记录" → 数据完整（防杀验收）；
  4. 白名单引导流程可用；
  5. 网页版导出 JSON → 安卓导入 → 回放/优化结果一致（互通验收）；
  6. 真实地图（OSM 瓦片）显示村庄路网。

## 7. 里程碑（对应网页版 M1~M5）

| 里程碑 | 内容 |
|---|---|
| A-M1 记录闭环 | 状态机移植 + 前台服务定位 + 检查点 + 防杀四层 + 真机锁屏验收 |
| A-M2 回放与收拾 | 平滑/分段/回放动画（Compose Canvas）+ 改名/合并（点选芯片）/拆分 |
| A-M3 优化与压轴 | Held-Karp + 三线对比 + 压轴 morph 动画 + 成绩单 |
| A-M4 跨年便利 | 清单/漏访/套名/历年成绩单 |
| A-M5 打磨 | 暖色年味主题精修 + 高德地图细节 + 数据互通验收 + APK 侧载流程 |

## 8. 风险与预留

| 风险 | 缓解 |
|---|---|
| 高德 Key 申请（需实名） | 个人开发者免费，提前申请；Key 与包名/SHA1 绑定 |
| 各 ROM 白名单路径差异 | 防杀文档维护路径矩阵，App 内图文引导 |
| 国产 ROM 无 GMS | 已修正：全部用高德（定位+地图），不依赖 Google 服务 |
| 状态机并发（定位线程 vs UI 线程） | 移植时加锁 + 并发测试（对照网页版 v5.16 的 `_infer_lock` 教训） |
| Compose Canvas 动画性能 | 轨迹点先抽稀再绘制（网页版已证明 ≤400 点流畅） |
| RTK 未来接入 | `LocationSource` 抽象接口：高德定位 / 蓝牙 NMEA 两种实现，随时可插 |

## 9. 与网页版的关系（双版本共存策略 · 已定案）

| 角色 | 网页版（保留，不删不废） | 安卓版（新） |
|---|---|---|
| 现场记录 | 应急备用（手机没电时借他人手机浏览器） | **主力**（锁屏/后台/防杀） |
| 复盘展示 | **主力**（电脑大屏看回放/三线动画体验最佳） | 手机查看 |
| 算法对照 | **111 项测试 = 移植的"标准答案"** | 直译产物，测试逐条对齐 |
| 数据 | 互通（数据格式文档为唯一契约） | 互通 |

**处理决定：**
1. **同一仓库**：安卓工程放 `android/` 子目录，网页版留在根目录；两条 CI 工作流按路径过滤互不干扰（改网页只发网页，改安卓只建安卓）；
2. **网页版冻结功能开发**：只修 bug，不加新功能——避免两端逻辑漂移；
3. **契约权威化**：`android/数据格式.md` 是唯一权威，任何格式变更必须两端同步 + 过互通测试；
4. 网页版继续免费挂在 Pages，零维护成本。
