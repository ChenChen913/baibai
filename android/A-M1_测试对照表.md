# A-M1 测试对照表（网页版 → Kotlin 直译基准）

> 用途：第②步"测试先行"的执行依据。每条网页测试 → Kotlin 测试一一对应，**名称、断言、数值逐位对齐**。
> 总计：**38 项**（geo 9 + state 23 + gps 错误 1 + JsonStore 4 + 并发冒烟 1）。
> 全部运行于 JVM/Robolectric，无需模拟器。

## 0. 公共辅助直译

| 网页（TS） | Kotlin |
|---|---|
| `const R = 6371000` | `const val R = 6371000.0`（`core/model/Constants.kt`） |
| `far(m)` 由 HOME 向北/东偏移 m 米 | 同公式 helper：`fun far(m: Double, dir: Dir = N): LatLng` |
| `fix(pos, acc=5)` | `fun fix(pos: LatLng, acc: Double = 5.0): Fix` |
| `const T0 = 1_700_000_000_000` | `const val T0 = 1_700_000_000_000L` |
| `expect(x).toBeCloseTo(y, n)` | `assertEquals(y, x, 10.0.pow(-n))` |
| `expect(fn).toThrow(/…/)` | `assertThrows<IllegalStateException> { fn() }`（错误信息含"非法转移"/"无有效定位"字样，用 `assertTrue(ex.message.contains(...))` 核对） |
| `expect(x).toEqual(y)`（对象） | 数据类默认 `equals` → `assertEquals(y, x)` |
| `structuredClone` | 数据类不可变拷贝 / `copy()` |

**kotlinx-serialization 注意**：所有 `@SerialName` 与契约字段名一致（含可选字段 `lowAcc`/`jump` 的 `encodeDefaults=false` 缺省语义，需专项测试）。

## 1. GeoTest（9 项）← `tests/geo.test.ts`

| # | 网页测试 | Kotlin 测试 | 关键断言 |
|---|---|---|---|
| 1 | haversineM·同点距离为 0 | `同点距离为0` | `assertEquals(0.0, d)` |
| 2 | haversineM·纬度方向 100m | `纬度方向100m约为100m` | ±0.5m 容差 |
| 3 | haversineM·经度方向 100m（31°N） | `经度方向100m约为100m` | ±0.5m 容差 |
| 4 | haversineM·往返精度 10m | `往返精度10m误差小于1e-6` | `abs(d-10.0) < 1e-6` |
| 5 | medianPos·三点取中间点 | 同名 | 精确相等 |
| 6 | medianPos·两点取排序后第一个 | `两点取中偏前` | 精确相等（**偶数取中偏前**语义不能漂移） |
| 7 | medianPos·空数组返回 null | `空数组返回null` | `assertNull` |
| 8 | nearest·找到最近节点 | 同名 | nodeId + dist 范围 |
| 9 | nearest·空列表 null 与 Infinity | 同名 | `assertNull` + `Double.POSITIVE_INFINITY` |

## 2. RecorderStateTest（23 项）← `tests/state.test.ts`

| # | 网页测试 | Kotlin 测试 | 关键断言 |
|---|---|---|---|
| 1 | 状态机·完整闭环 | 同名 | 状态轨迹 + autoNo 1/2 + finish ok |
| 2 | 状态机·非法转移被拒绝 | 同名 | 四类非法转移各自抛异常 |
| 3 | 状态机·WALKING 直接结束（SPEC v1.1 修正） | 同名 | `finish` 于 WALKING 成功 |
| 4 | 状态机·无定位 start 抛错 / fallback 可用 | 同名 | 两条路径 |
| 5 | 状态机·无定位 pause 抛错 | 同名 | 抛异常 |
| 6 | 10m 合并·8m 内重复暂停合并 | 同名 | 同 id、nodes=1、visits=2 |
| 7 | 10m 合并·边界 9.999 合并 / 10.001 新建 | 同名 | 两个距离点分支 |
| 8 | 10m 合并·低精度 fix 标 lowAcc | 同名 | lowAcc=true + 用原始最后一点 |
| 9 | 10m 合并·高精度优先 | 同名 | lowAcc 缺省 + 两点中偏前 |
| 10 | 10m 合并·5m 合并到 Home（D20） | 同名 | nodeId='home'、nodes 不变 |
| 11 | 自动编号·按拜访顺序不含 Home | 同名 | autoNo 1/2/3 |
| 12 | 自动编号·撤销后复用编号 | 同名 | [1,2] |
| 13 | 撤销·撤销链回溯到待机 | 同名 | 四连撤销的状态/数据序列 |
| 14 | 撤销·撤销"合并"的暂停只删访问 | 同名 | visits=1、节点保留 |
| 15 | 撤销·撤销结束回到结束前状态 | 同名 | prev 状态还原 |
| 16 | 撤销·无可撤销返回 false | 同名 | 布尔返回值 |
| 17 | Home 起止·500m 拒绝可强制 | 同名 | ok=false + distM≈500 + force 路径 |
| 18 | 跳变防护·2s 内 500m 标 jump | 同名 | jump 标记 + 超时不标 |
| 19 | 跳变防护·非 WALKING 记点抛错 | 同名 | 抛异常 |
| 20 | 出行方式·骑车段 bike + 到户自动回走路（D19） | 同名 | visits.mode + currentMode 复位 |
| 21 | 出行方式·撤销暂停还原方式 | 同名 | currentMode 回 bike |
| 22 | 检查点·JSON 往返一致 | `检查点序列化往返一致` | 序列化/反序列化后快照逐字段相等 |
| 23 | 检查点·恢复后可继续且撤销历史可用 | 同名 | resume/undo 行为 |

**Kotlin 专属注意**：RecorderState 的 public 方法加 `@Synchronized`；测试 #1~#23 全部照搬语义；`checkpoint()` 返回不可变数据类，`restore()` 为伴生对象工厂。

## 3. GpsErrorsTest（1 项）← `tests/gps.test.ts`

| # | 网页测试 | Kotlin 测试 | 关键断言 |
|---|---|---|---|
| 1 | describeGpsError 错误码映射 | 同名 | 1→denied、2→unavailable、3→timeout、0/99→unavailable |

## 4. JsonStoreTest（4 项）← `tests/db.test.ts`

> 差异：网页用 fake-indexeddb；Kotlin 用 **JUnit `@TempDir` 临时目录**写真实文件（更强：顺带验证原子写与路径）。

| # | 网页测试 | Kotlin 测试 | 关键断言 |
|---|---|---|---|
| 1 | 活跃检查点保存/读取往返一致 | 同名 | 逐字段相等（序列化后反序列化） |
| 2 | 重复保存覆盖旧检查点 | 同名 | 新值生效 |
| 3 | clear 后读不到 | 同名 | 返回 null |
| 4 | 历史会话保存/列表/导出 | 同名 | list 长度 + 导出 JSON 结构（`app=baibai`） |

## 5. 并发冒烟（新增 1 项，网页版 `_infer_lock` 教训）

| 测试 | 设计 |
|---|---|
| `RecorderState并发冒烟` | 起 2 线程 × 5000 次：一线程循环 开始→暂停→继续→结束→撤销，另一线程循环 快照+检查点读取；断言：无异常、最终状态 ∈ 合法集合、快照字段一致 |

## 6. 本里程碑不移植的测试（延后到对应里程碑）

- `smooth.test.ts` / `track.test.ts` → A-M2
- `tsp.test.ts` / `optimize.test.ts` / `polyline.test.ts` → A-M3
- `review.test.ts` / `plan.test.ts` → A-M2/A-M4
- `playback.test.ts` → A-M2
- `village-sim.test.ts`（昌乐县 15/20 户仿真）→ **A-M3 完成后**（依赖 track/optimize），数值必须与网页版逐位一致
- `ui-smoke.test.ts` → A-M1 的 Compose 冒烟（按钮可点/回调触发），另立对照表
