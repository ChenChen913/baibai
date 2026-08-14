# A-M1 记录闭环 · 规格（Android 版）

> 对应 `android/SPEC.md` §7。本规格**经用户确认后**才进入"测试先行 → 实现"。
> 验收主线：**锁屏 10 分钟轨迹连续 + 强杀进程后检查点续录**。

## 1. 范围

**做：**
- Gradle 工程骨架（**双模块**，见 §2）；
- 数据模型 + JSON 契约直译（core 模块，格式= `数据格式.md`）；
- `Geo.kt` + `RecorderState.kt` 逐函数直译 + 全部对照测试；
- `JsonStore` 检查点原子写（10s + 按键）；
- 前台服务定位 + 四层防杀（按 `防杀与后台定位.md` 全量实现）；
- 记录页 Compose 最小可用版（驾驶舱简化）；
- 看门狗 + 定位错误可见化（同网页版）；
- Android CI 工作流（云端构建 APK + 跑测试）。

**不做（后续里程碑）：** 回放/平滑/优化（A-M2/M3）、清单/漏访/套名（A-M4）、视觉精修/高德地图（A-M5 或 Key 到位后）、RTK。

## 2. 工程骨架

- **双模块**：`core`（Kotlin/JVM 纯算法+模型+测试，**零 Android 依赖**——算法测试秒级跑完）+ `app`（Android + Compose + Service + UI）；
- 版本：Kotlin 2.0.x · AGP 8.5.x · Compose BOM（2024.x）· kotlinx-serialization-json 1.7.x · JUnit5 · **minSdk 26（Android 8.0）· targetSdk 34**；
- **包名：`io.github.chenchen913.baibai`**（高德 Key 与包名+SHA1 绑定，定死后不可改——见 §10 决策点）；
- 目录：

```
android/
├── settings.gradle.kts
├── gradle/libs.versions.toml        # 版本目录
├── core/
│   ├── src/main/kotlin/.../core/
│   │   ├── model/    # LatLng/Fix/HouseNode/Visit/TrackPoint/SessionData/Plan/Checkpoint + 常量
│   │   ├── geo/      # Geo.kt（haversine/中位数/最近节点）
│   │   ├── state/    # RecorderState.kt（状态机，线程安全）
│   │   └── json/     # 序列化适配（@SerialName 对齐契约字段名）
│   └── src/test/kotlin/.../core/    # 对照测试（先红后绿）
└── app/
    ├── src/main/AndroidManifest.xml # 权限 + foregroundServiceType="location"
    ├── src/main/kotlin/.../app/
    │   ├── MainActivity.kt          # Compose 单 Activity
    │   ├── record/RecordScreen.kt   # 驾驶舱简化版
    │   ├── location/LocationSource.kt / SystemLocationSource.kt / AmapLocationSource.kt(占位)
    │   ├── location/LocationService.kt  # 前台服务 + START_STICKY + 通知
    │   ├── store/JsonStore.kt       # filesDir 原子写
    │   ├── vm/RecordViewModel.kt    # StateFlow 接线
    │   └── whitelist/WhitelistGuideScreen.kt  # 白名单图文引导
    └── src/test/                    # Robolectric 测试（JsonStore/服务逻辑）
```

## 3. 直译对照（测试即契约，先红后绿）

| 网页模块 | Kotlin 目标 | 直译要点 |
|---|---|---|
| `geo.ts` | `core/geo/Geo.kt` | haversineM / medianPos / nearest，语义逐位一致 |
| `state.ts` | `core/state/RecorderState.kt` | 状态机全套 + LIFO 撤销 + 10m 合并 + 自动编号 + 跳变防护 + checkpoint/restore；**线程安全**：全部入口 `synchronized`（定位线程 vs UI 线程并发，对照网页版 `_infer_lock` 教训，补一条并发冒烟测试） |
| `gps.ts` 错误分类 | `app/location/GpsErrors.kt` | describeGpsError 同映射 |
| `db.ts` | `app/store/JsonStore.kt` | saveActive/loadActive/clearActive/saveSession/listSessions/savePlan/loadPlan + exportAllJson；**原子写**：tmp → fsync → rename |
| 常量 | `core/model/Constants.kt` | 全部来自 `数据格式.md` §9，禁止漂移 |

**JSON 序列化**：kotlinx-serialization，字段名用 `@SerialName` 精确对齐契约（含可选字段 `lowAcc`/`jump` 的缺省处理）。

## 4. 前台服务与定位

- **`LocationSource` 抽象**（为高德/RTK 预留）：
  - `SystemLocationSource`：`LocationManager` GPS，1s 间隔——**零 Key、零外部依赖，先跑通全链路**；
  - `AmapLocationSource`：占位实现，高德 Key 到位后填充（定位精度/连续定位参数届时定）；
- **LocationService**：`foregroundServiceType="location"` + 常驻通知（"🎙 记录中 · 已拜访 N 户"）+ `START_STICKY`；回调线程加锁写 `RecorderState`；
- **四层防杀**：前台服务+通知 → START_STICKY → 白名单引导页（按 ROM 路径矩阵）→ 检查点续录；
- **权限**：定位 + 通知（Android 13+）双请求；拒绝/超时 → 中文提示文案（同网页版）；
- **看门狗**：开始拜年后 30s 无定位 → 提示（权限/室内/微信浏览器无关——原生无此问题，重点是权限与室内）。

## 5. 记录页 Compose（最小可用版）

驾驶舱简化：状态大字 / 统计（户数·用时）/ 主按钮（开始·暂停·继续·结束·撤销）/ 出行方式切换 / 清单·历史入口（本里程碑 disabled 占位）；白名单引导页在首次"开始拜年"前弹出一次（"我已完成"确认后不再打扰）。

## 6. 测试清单（对照网页版测试逐条直译）

| 网页测试文件 | Kotlin 测试 | 条数 |
|---|---|---|
| `geo.test.ts` | `GeoTest` | 9 |
| `state.test.ts` | `RecorderStateTest` | 23 |
| `gps.test.ts`（错误映射） | `GpsErrorsTest` | 1 |
| `db.test.ts` | `JsonStoreTest`（fake-indexeddb → JVM 临时目录真文件） | 4 |
| 新增：并发冒烟 | 两线程同时操作状态机不崩溃不丢状态 | 1 |

**合计 ≈ 38 项**，全部在 `core`/`app` 的 JVM/Robolectric 环境运行，无需模拟器。

## 7. CI（云端，本地零工具链）

`.github/workflows/android.yml`：仅 `android/**` 变更触发 → JDK17 + SDK → `gradlew test`（core+app 测试）→ `assembleDebug` → 上传 APK 产物。用户侧：`gh run download` 取 APK → 装真机。

## 8. 验收标准

- **云端**：全部测试绿 + debug APK 构建成功；
- **真机（用户执行）**：
  1. 锁屏 10 分钟 → 解锁 → 轨迹连续无断点（**A-M1 核心验收**）；
  2. 强杀（最近任务划掉）→ 重开 → "检测到未完成的记录" → 继续 → 数据完整；
  3. 白名单引导页图文正确展示（对照你手机 ROM 的路径）；
  4. 拒绝定位权限 → 中文提示出现；
  5. 通知常驻，文案随状态变化（记录中 N 户 / 在某户）。

## 9. 决策点（需要你确认，确认后定死）

> **状态（2026-08-13）**：三轮等待未收到确认，按流程弹性规则**以推荐默认值先行开工**（见 A-M1 测试对照表与工程实现）。全部五项均为低成本可逆改动：包名可机械重命名（在高德 Key 申请前随时改）、双模块可合并为单模块、定位源为接口抽象可切换、minSdk 一行配置、应用名/图标为资源文件。**你如有不同意见，随时指出，我按你的决定调整。**

1. **包名 `io.github.chenchen913.baibai`** 可以吗？（高德 Key 与它绑定，确认后不可改）
2. **双模块 core/app** 结构认可吗？
3. **定位先用系统 LocationManager（免 Key）跑通全链路**，高德 SDK 等你申请到 Key 再接——认可吗？（这也意味着 A-M1 不需要你先去申请高德 Key）
4. **minSdk 26（Android 8.0+）**——你的手机安卓版本是多少？（低于 8 才需要调）
5. 应用名就用 **"拜拜"**，图标先用红底金灯笼 PNG（后续可换）——确认吗？
