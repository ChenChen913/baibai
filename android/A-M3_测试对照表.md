# A-M3 测试对照表（网页版 → Kotlin 直译基准）

> **19 项**（tsp 4 + optimize 6 + polyline 7 + village-sim 2）。数值与网页版对照；公共辅助沿用 A-M1/A-M2 对照表。

## 1. TspTest（4 项）← `tests/tsp.test.ts`

| # | 网页测试 | 关键断言 |
|---|---|---|
| 1 | n=1 与 n=2 平凡情形 | order/cost/exact 精确值 |
| 2 | n=6 随机 10 组 = 全排列暴力解 | `exact=true`、合法排列、cost 与暴力解 1e-6 一致 |
| 3 | n=16 边界仍精确且排列合法 | exact、合法排列 |
| 4 | n>16 启发式：exact=false 且排列合法 | cost>0 |

## 2. OptimizeTest（6 项）← `tests/optimize.test.ts`（demo 3 项已在 A-M2）

| # | 网页测试 | 关键断言 |
|---|---|---|
| 1 | 空会话三线 home 单点零成本 | order/exact/cost |
| 2 | 飞行线 cost = 顺序边 haversine 之和 | 1e-3 |
| 3 | 三条路线覆盖全部节点且 home 打头 | 集合相等、edges 数 |
| 4 | 边 known 标记与实走对一致（demo 必有未知对） | known 集合等于实走对集合 |
| 5 | 未知距离边 = 直线 × 1.3；距离线 cost > 飞行线 | 系数与大小关系 |
| 6 | 同一对多次实走取最短耗时（D15） | 全连通两户夹具：cost=Σ最短、<2.5s |

## 3. PolylineTest（7 项）← `tests/polyline.test.ts`

| # | 网页测试 | 关键断言 |
|---|---|---|
| 1 | routePolyline 闭合：首尾同点、点数=顺序+1 | home 起止 |
| 2 | resample 首尾保持、点数正确、等距中点 | out[5]=(10,0) |
| 3 | resample 退化折线不崩溃 | m 个重复点 |
| 4 | resample 空与单点 | 空→空；单点→m 个重复点 |
| 5 | lerp t=0→a、t=1→b、中间线性 | 端点与中点 |
| 6 | scorecard demo：口径正确、节省率为正 | 三线均省、含骑行、全天≥路上 |
| 7 | scorecard 空会话全零 | 全零 |

## 4. VillageSimTest（2 项）← `tests/village-sim.test.ts`

| # | 网页测试 | 关键断言 |
|---|---|---|
| 1 | 昌乐县 15 户：无误合并、回访合并、三线节省为正、精确解、性能达标 | nodes=15、visits=16、exact、<2000ms、节省率>0；打印报告对照 |
| 2 | 昌乐县 20 户：无误合并、启发式、性能、跳变鲁棒 | nodes=20、visits=21、!exact、<2000ms；插入跳变点后全管线不抛 |

> 口径说明：V8 与 JVM 的 `sqrt/cos/log` 末位可能不同，报告数值用 ±0.5% 容差与网页版结果（15 户省 41.5% / 20 户省 33.3%）比对，不逐位硬等。

## 5. 本里程碑不移植的测试（延后）

- `plan.test.ts` / 清单 → **A-M4**
- 网页版 `ui-smoke.test.ts` 的三线对比交互 → A-M3 app 层实现时的 Compose 冒烟，另立对照
