# A-M2 测试对照表（网页版 → Kotlin 直译基准）

> 用途：第②步"测试先行"的执行依据。**39 项**（smooth 12 + track 8 + playback 7 + review 9 + demo 3），另有 Robolectric 冒烟 3 项。
> 数值必须与网页版**逐位一致**；公共辅助直译沿用 A-M1 对照表（far/fix/T0/R/容差规则）。

## 1. SmoothTest（12 项）← `tests/smooth.test.ts`

| # | 网页测试 | 关键断言 |
|---|---|---|
| 1 | jumpSplit·无 jump 为单段 | 段数 1、点数 3 |
| 2 | jumpSplit·中间 jump 切两段，jump 点归后段 | 段内坐标序列 |
| 3 | jumpSplit·开头 jump 不产生空段 | 段数 1、点数 2 |
| 4 | movingAverage·长度不变、保留 t/acc/jump | 长度与元字段 |
| 5 | movingAverage·共线等距点不变 | 中间点原值（1e-9） |
| 6 | movingAverage·端点保持原坐标，中间点取窗口均值 | 端点不动、中点精确 |
| 7 | smoothTrack·长度不变 | 4 点 → 4 点 |
| 8 | smoothTrack·窗口不跨 jump 段 | jump 点与其段邻居不被跨段拉拽 |
| 9 | douglasPeucker·直线仅保留两端点 | 输出 = [首, 尾] |
| 10 | douglasPeucker·eps=0 全保留 | 长度不变 |
| 11 | douglasPeucker·直角拐点保留 | 输出等于输入 |
| 12 | douglasPeucker·空与两点直通 | 空→空；两点→原样 |

## 2. TrackTest（8 项）← `tests/track.test.ts`

| # | 网页测试 | 关键断言 |
|---|---|---|
| 1 | buildEdges·三段场景 3 条边、时间窗过滤、距离为正 | 边 id 序列、raw 时间戳、distM>0 |
| 2 | buildEdges·出行方式归属 | walk/bike/walk（D19 自动回走路） |
| 3 | buildEdges·中途回 Home 多段循环 | home→A→home→C→home |
| 4 | buildEdges·未结束不生成尾边 | 无回 home 尾边 |
| 5 | projectToView·所有点落在视口内 | 边界断言 |
| 6 | projectToView·等比不变形、北在上 | dx==dy（1e-9）、y 方向 |
| 7 | toSvgPath·起笔 M 且点数一致 | 命令计数 |
| 8 | toSvgPath·空点集返回空串 | 空串 |

> 注：`toSvgPath` 保留为纯函数（对照基准用途），Compose Canvas 用 `projectToView` 坐标点。

## 3. PlaybackTest（7 项）← `tests/playback.test.ts`

| # | 网页测试 | 关键断言 |
|---|---|---|
| 1 | buildPlan·抽稀后首尾保留、时间轴连续 | 首尾 t、totalMs=6000、单调 |
| 2 | buildPlan·拐点被保留 | pts>2 |
| 3 | buildPlan·空会话返回空计划 | 空 pts、totalMs=0 |
| 4 | positionAt·起点/终点/越界夹取 | 端点与夹取行为 |
| 5 | positionAt·段间线性插值 | 中点插值（1e-6） |
| 6 | positionAt·空计划返回 null | null |
| 7 | fractionAt·0..1 夹取 | 0/1/越界/空计划 |

## 4. ReviewTest（9 项）← `tests/review.test.ts`

| # | 网页测试 | 关键断言 |
|---|---|---|
| 1 | renameNode·改名生效 | 名字更新、其余数据不动 |
| 2 | renameNode·home 不可改名 | 返回原实例 |
| 3 | mergeNodes·访问并入、节点删除、空名继承 drop 名 | 访问重挂、名字继承规则 |
| 4 | mergeNodes·不存在的 id 原样返回 | 原实例 |
| 5 | splitVisit·拆出第二次到访为新户，坐标取 arriveT 前最近点 | 新节点 pos、编号重排 [1,2,3] |
| 6 | splitVisit·home 访问不可拆 | 原实例 |
| 7 | splitVisit·无轨迹点可借时原样返回 | 原实例 |
| 8 | removePoint·按时间戳剔除单个点 | 长度减一、点消失 |
| 9 | renumberNodes·按首次到访顺序编号 | 1/2 |

## 5. DemoTest（3 项）← `tests/optimize.test.ts` 的 demo 部分

| # | 网页测试 | 关键断言 |
|---|---|---|
| 1 | 结构合法：8 户 8 访、时间单调、时长>0 | 户数/访问数/点数>100/单调/停留>0/含 1 跳变 |
| 2 | 可被 buildPlan 与 buildEdges 消费 | pts>10、totalMs>0、边数>0 |
| 3 | 确定性：两次生成完全一致 | 逐字段相等 |

> 注：demo 的 `optimizeSession` 消费断言延后到 A-M3（依赖 Optimize.kt）。

## 6. Robolectric 冒烟（3 项，新增）

| 测试 | 设计 |
|---|---|
| 回顾页操作·改名落盘 | 经 `Review.renameNode` → JsonStore 覆盖写 → 重读一致 |
| 回顾页操作·合并落盘 | mergeNodes → 会话文件重读：节点数 -1、访问重挂 |
| 回顾页操作·跳变剔除落盘 | removePoint 全部跳变点 → 重读：无 jump 标记 |

## 7. 本里程碑不移植的测试（延后）

- `tsp.test.ts` / `optimize.test.ts`（优化三线）/ `polyline.test.ts` → **A-M3**
- `village-sim.test.ts`（昌乐县 15/20 户仿真）→ **A-M3 完成后**（依赖 Optimize），数值逐位一致
- `plan.test.ts` / 清单 → **A-M4**
- 网页版 `ui-smoke.test.ts` → A-M2 的 Compose 冒烟（历史/回顾页按钮与交互），实现时另立对照
