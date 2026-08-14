# 第三方声明（Third-Party Notices）

baibai（拜拜）为原创项目，以下列出所使用/借鉴的第三方项目与数据源的版权与许可信息。

## 直接依赖（随代码分发）

| 项目 | 版本 | 许可 | 备注 |
|---|---|---|---|
| Leaflet | ^1.9.4 | BSD-2-Clause | 网页版与安卓 WebView 的地图交互引擎；源码随 node_modules / 安卓 assets（android/app/src/main/assets/baibai_map/leaflet.js）分发 |
| Vite / Vitest / TypeScript / jsdom / fake-indexeddb | 见 package.json | MIT 等 | 仅开发期依赖，不进入产物 |

## 地图数据与瓦片服务

| 来源 | 许可 | 使用方式 |
|---|---|---|
| OpenStreetMap 瓦片（tile.openstreetmap.org / OSM-HOT） | 数据 ODbL，© OpenStreetMap contributors | 网页版兜底瓦片（高德瓦片失败时） |
| 高德地图瓦片（webrd/webst *.is.autonavi.com） | 遵循高德瓦片服务条款 | 两端主底图（普通地图 + 卫星图），免 Key |

## 借鉴（未直接复用代码）

| 项目 | 许可 | 借鉴内容 |
|---|---|---|
| Organic Maps（organicmaps/organicmaps） | 代码 Apache-2.0；地图数据 ODbL | 离线优先地图架构、定位省电调度（动态采样间隔）、HTTP Range 断点续传下载、县级切图流程等**设计思路** |

## 合规说明

- 若未来直接复用 Organic Maps 代码 → 保留其 Apache-2.0 版权声明与 NOTICE；
- 若未来使用其 .mwm 地图数据 → 须在可见位置署名 "Map data © OpenStreetMap and Organic Maps" + 可点击链接，禁止白标/去品牌化；
- OSM 数据（含瓦片）→ 署名 © OpenStreetMap contributors（已在应用地图卡底部展示）。
