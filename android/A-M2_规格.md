# A-M2 回放与收拾 · 规格（Android 版）

> 对应 `android/SPEC.md` §7。**经用户确认后**才进入"测试先行 → 实现"。
> 前置依赖：A-M1 记录闭环已代码完成（真机验收可与本规格审阅并行）。

## 1. 范围

**做：**
- core 直译：`Smooth.kt`（跳变切段/段内滑动平均/Douglas-Peucker）、`Track.kt`（buildEdges 边分段/视口投影）、`Playback.kt`（回放计划/时间插值）、`Review.kt`（改名/合并/拆分/剔除异常）、`Demo.kt`（演示数据生成器）；
- app 层：**历史列表页**（会话列表 → 点进回顾）、**回顾页**（Canvas 轨迹回放动画 + 收拾工具：改名/合并点选芯片/拆分/跳变剔除）；
- 从回顾页进入"三线对比"的入口（A-M3 实现目标页，本里程碑先放占位按钮）。

**不做：** 三线优化与压轴动画（A-M3）、清单/漏访/套名（A-M4）、高德地图与视觉精修（A-M5）。

## 2. 模块直译对照

| 网页模块 | Kotlin 目标 | 要点 |
|---|---|---|
| `smooth.ts` | `core/smooth/Smooth.kt` | jumpSplit / movingAverage（端点保持+跳变跳过）/ smoothTrack / douglasPeuckerKeep + douglasPeucker |
| `track.ts` | `core/track/Track.kt` | buildEdges（时间窗分段、D19 方式归属、中途回 Home 多段循环）；boundsOf/projectToView/toSvgPath 保留为**纯视口投影函数**（Compose Canvas 用坐标点而非 SVG path 字符串，`toSvgPath` 可留作对照测试用途） |
| `playback.ts` | `core/playback/Playback.kt` | buildPlan（DP 抽稀保留时间轴）/ positionAt / fractionAt |
| `review.ts` | `core/review/Review.kt` | renameNode / mergeNodes（空名继承）/ splitVisit（坐标借 arriveT 前最近点）/ removePoint / renumberNodes |
| `demo.ts` | `core/demo/Demo.kt` | 8 户演示会话（2026-02-17 春节、绕路顺序、骑行段、跳变点；确定性输出） |
| `db.ts`（部分） | `JsonStore` 已具备 | 无需新工作 |

**kotlinx-serialization 注意**：TrackPoint/Visit 等模型已在 A-M1 落地，无需改动；`Edge` 等衍生结构仅内存用，不序列化。

## 3. app 层 UI

### 3.1 历史列表页（HistoryScreen）
- 会话列表：日期 · N 户 · 距离 · 绕路率（绕路率依赖 scorecard——A-M3 的 Optimize 才直译，**本里程碑先显示 日期/N 户/到访次数**，A-M3 补上绕路率）；
- 空态："还没有记录。大年初一，出发！"；点击会话 → 回顾页。

### 3.2 回顾页（ReviewScreen）
- **回放区**：Compose Canvas 绘制平滑抽稀轨迹（描边动画 + 行进光点），播放/暂停/重置 + 1x/2x/4x 变速；节点标注（户名/编号 + 家）；
- **收拾区**：
  - 改名：每户输入框 + 保存；
  - 合并：**点选芯片**交互（沿用网页版最终设计：第一个保留、第二个并入，选满两户才可合并）；
  - 拆分：每户"到访 N 次"列表，每次到访一个"拆成新户"按钮；
  - 异常跳变点：列表 + 单个剔除 + 全部剔除；
- 所有收拾操作 → 自动保存到 JsonStore（覆盖写该会话）。

### 3.3 记录页入口
- 记录页工具条增加"历史"按钮（进入历史列表）；结束拜年后自动跳转回顾页（沿用网页版行为）。

## 4. 测试对照（A-M2 共 39 项，见 `A-M2_测试对照表.md`）

| 网页测试文件 | 项数 |
|---|---|
| `smooth.test.ts` | 12 |
| `track.test.ts` | 8 |
| `playback.test.ts` | 7 |
| `review.test.ts` | 9 |
| `demo.test.ts`（结构/可消费/确定性） | 3 |

另加 **Robolectric 冒烟**：回顾页操作（改名/合并/拆分/剔除）经 Hub 后 JsonStore 落盘正确（约 3 项）。

## 5. 验收标准

- **云端**：全部测试绿（core 现有 38 + 新增 39 + app 冒烟 ≈ 80 项）+ APK 构建成功；
- **数值一致**：同一会话数据在网页版与安卓版算出的边距离/回放抽稀点数逐位一致（互通验收用例：网页版导出 JSON → 安卓导入 → 回放一致）；
- **真机**：回放动画流畅（400 点内 Canvas）；合并/拆分/改名操作即时生效；异常跳变剔除后回放不再跳动。

## 6. 决策点（需确认）

1. **回顾页入口**：历史列表 → 点会话 → 回顾页（推荐）；记录页工具条加"历史"按钮；
2. **绕路率显示**：本里程碑历史列表只显示基础统计（绕路率等 A-M3 scorecard 直译后补上）——认可？
3. **回放动画风格**：沿用网页版（红色轨迹描边 + 光点行进），Compose Canvas 实现——认可？
