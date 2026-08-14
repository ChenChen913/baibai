# Organic Maps 调研报告与 baibai 移植规划

> 调研对象：organicmaps-master/（13,259 文件 / 582MB；C++ 1478 cpp + 1247 hpp，Java 435，Kotlin 24，Python 138）
> 调研方式：我直接读仓库核实 + 4 个并行子任务深挖（渲染引擎 / 数据管线 / Android 平台层 / 许可与构建）。
> 本文所有数字均有仓库内证据（文件路径见各节）。

## 一、Organic Maps 到底是怎么做的（一页纸讲明白）

**核心一句话：它不是"在线地图"，而是"手机里的离线地图"。**

| 层 | Organic Maps 的做法 | 证据 |
|---|---|---|
| 底图数据 | OpenStreetMap 全球数据 → 自研 generator_tool（C++23）编译成**私有二进制矢量格式 .mwm**（分块容器，非 protobuf，格式 v1→v11 快速演进、无公开文档） | docs/MAPS.md、libs/coding/files_container.hpp |
| 离线分发 | 世界按 data/borders/ 1159 个区域切块，中国**省级**粒度；用户按需下载 .mwm 存本地（山东省 ≈ **98MB**、广东 211MB、上海 39MB）；**支持自定义县级切图**（GeoJSON 边界 + osmium extract，README 的 Custom maps 流程），县 ≈ 2~20MB、生成分钟级；下载 URL = maps/{版本}/{文件}.mwm + diffs/ 增量补丁 | data/countries.json（China_Shandong s=103,011,291）、data/borders/China_*.poly、libs/platform/downloader_utils.cpp |
| 渲染 | 自研 C++ 引擎 **drape**（419 文件/2.3MB，OpenGL ES 3.0 / Vulkan(API≥26) / Metal 三后端），MapCSS 样式 → drules_*.bin 二进制绘制规则 | libs/drape/、libs/shaders/gl_shaders_preprocessor.py、docs/STYLES.md |
| 平台桥 | 安卓 = 原生 View + **SurfaceView + JNI**：MapView(SurfaceView) → Map.cpp nativeCreateEngine → Framework::CreateDrapeEngine；单 .so（15~25MB/ABI） | android/sdk/.../MapView.java、sdk/src/main/cpp/.../Map.cpp、Framework.cpp:174 |
| 定位 | Provider 抽象 + 动态间隔调度（移动 100ms/静止 3000ms）+ 劣质点过滤 + A-GPS 注入；后台仅用前台服务（无后台定位权限） | sdk/location/core/BaseLocationProvider.java、app/.../LocationHelper.java |
| 轨迹 | **SDK 自带 TrackRecorder**（start/stop/save + 实时统计回调），轨迹由 DrapeApi::AddLine 直接注入渲染（不需要 .mwm 也能画线） | sdk/src/main/cpp/.../TrackRecorder.cpp、libs/drape_frontend/drape_api.hpp |
| 下载 | 前台服务（DATA_SYNC）+ HTTP Range 断点续传（.downloading/.resume 保进度） | libs/platform/http_client.cpp、libs/storage/http_map_files_downloader.cpp |
| 许可 | **代码 Apache-2.0**；**地图数据 ODbL**，DATA_LICENSE.txt 要求可见署名 "Organic Maps Project" + 可点击链接，**禁止白标/去品牌化** | README.md、LICENSES/、DATA_LICENSE.txt |

**它"地图永远能显示"的原因 = 数据在本地 + 渲染在本地，网络只是锦上添花。**
这是 baibai 前几轮反复失败的对立面：我们一直依赖在线瓦片（OSM 国内慢 → 高德仍要网），断了网就只剩纸色底。

## 二、baibai 现状与差距

| 维度 | baibai 现状 | 差距 |
|---|---|---|
| 底图 | WebView + Leaflet + 高德在线瓦片（地图/卫星双图层，GCJ-02 已转） | **无离线保障**：断网=空白；瓦片缓存只有 WebView 自带 HTTP 缓存（无主动预载、无容量管理） |
| 渲染 | WebView 内 JS/SVG 画轨迹 | 够用但非原生；量大时性能受限 |
| 定位 | LocationManager GPS+网络双源、3fix 定 Home、recent10 中位数 | 已接近 OM 思路；缺"静止降频"省电调度 |
| 下载 | 无（不需要大文件） | 若做离线包则需要断点续传（可照抄 OM 做法） |

## 三、移植路线评估（A/B/C 三档）

### A 档：整库嵌入「Organic Maps SDK」
OM 官方把安卓端拆成可发布 SDK（android/sdk，包名 app.organicmaps.sdk，另有官方示例仓 organicmaps/api-android），
且**已通过 android/groovy/publishing.gradle 发布到 Maven Central**（POM 双许可：Apache-2.0 + Binary Data License）。
因此 A 档有两条子路：
- **A1 依赖官方 Maven artifact**（最省事）：gradle 直接引 app.organicmaps.sdk，不编译 C++；但需确认 artifact 的版本新鲜度与 arm64 覆盖，且仍要自己下载/分发 .mwm 数据 + 满足署名。
- **A2 源码级整库编译**：整 C++ 核心（NDK 29.0.14206865 + CMake 3.22.1+ + JDK17 + AGP 9.2.1 + compileSdk 36 + ≥30GB 磁盘，冷编数十分钟~小时级；核心约 15 万行 C++、C++ 源码 12.6MB + 3party 11.3MB）；.so 15~25MB/ABI；运行时资产 World.mwm 61MB + 字体样式，**最小 APK ≈ 40~85MB**；山东省离线包另需下载 ~98MB。

- **收益**：100% 离线矢量地图、自带轨迹录制、专业渲染与路网。
- **合规**：代码可复用；但必须满足 ODbL 署名 + **禁止白标**（baibai 是个人玩具，署名没问题，但要接受"地图是 Organic Maps 提供的"品牌呈现）。
- **风险**：依赖 OM 版本演进（.mwm 格式无公开文档、数据必须与 app 版本匹配）；baibai CI 构建时间暴涨；与 baibai 现有 Compose/AGP 工程整合成本高。另有两个工程细节：① 底图改为首启下载后最小单 ABI APK 可降到 **20~30MB**（内置则 40~85MB）；② 引擎初始化有严格顺序约束（先 setupWidgets 注入布局、LocationHelper 必须先于引擎创建，否则 ASSERT）。
- **结论**：工程量与维护代价对"个人拜年轨迹玩具"明显过重。**不推荐作为首选；若将来真要嵌入式能力，A1（Maven artifact）优先于 A2（源码编译）。**

### B 档：剥离 drape 做"小渲染库"
- **不可行**：核心渲染三件套实测 431 文件 / 61,618 行（drape 181 文件 23,293 行 + drape_frontend 227 文件 35,817 行 + shaders 23 文件 2,508 行）；再叠加必需底层 base/coding/geometry/platform/indexer ≈ 9.4 万行，**最小切面 ≈ 15 万行 C++**；drape 硬耦合 indexer/platform/geometry/coding/base + freetype/harfbuzz/ICU/expat（libs/drape/CMakeLists.txt 的 DRAPE_LINK_LIBRARIES 直接证明）；GL ES 3.0 硬约束（gl_shaders_preprocessor.py:325）；底图必须吃 .mwm；需 fork 仓库改 CMake 解耦。
- **补充发现 1**：drape 是纯渲染层、不含地图语义；真正有意义的复用边界是更上层的 map（Framework 聚合类）——抽 drape 收益低、代价高。
- **补充发现 2**：若**只画轨迹/标记、不要底图**，理论上可砍掉 indexer 的 MWM 数据通路（DrapeApi::AddLine 可直接注入任意折线，不需要 .mwm）——但仍有 overlay_handle.hpp→indexer 的浅耦合要 fork 改造，且"没有底图的渲染器"对 baibai 无意义。
- **结论**：**否决**（两个子任务独立得出同一结论）。

### C 档：借"离线优先"思想 + 轻量引擎（推荐）
OM 真正值得移植的是**架构思想**：底图必须能离线。落地用行业标准轻方案：

- **C1（轻，1~2 天）**：保留现有 WebView+Leaflet+高德瓦片，新增**瓦片预缓存**——
  - 按用户 Home 周边 bbox 分缩放级（z13~z17）预下载瓦片到本地（WebView HTTP 缓存显式预热 + 容量上限 LRU）；
  - 断网自动走缓存；在线仍走网络（网络优先）；
  - 网页版 Service Worker 同步扩展（现有 TILE_CACHE 只认 OSM 域名且上限 600 条，需支持高德域名 + 分级容量）。
  - **目标：你家方圆几公里的地图，装上就永远能看。**
- **C2（中，1~2 周，可选进阶）**：换 **MapLibre GL Native**（开源、BSD 许可）+ **PMTiles/MBTiles 离线矢量底图**：
  - 用 OSM 数据为"昌乐县"自生成矢量底图（县 ≈ 2~20MB、分钟级生成；切图流程直接借鉴 OM 的 GeoJSON 边界 + osmium extract 做法，再走 Geofabrik pbf）；
  - 离线矢量渲染（比瓦片省 10 倍流量与空间）、可自定义样式（借 OM 的 MapCSS 配色思路）；
  - 卫星图层继续高德在线瓦片 + 缓存。
  - 这是把 OM 的离线体验"用 1/10 的工程量"搬到 baibai。
- **C3（借鉴组件，零新依赖，3~5 天）**：照抄 OM 的工程细节到 baibai 现有代码——
  - 定位省电调度（移动 100ms / 静止 3000ms 动态切换，LocationHelper.java 的做法）；
  - 断点续传下载器（Range/206/.resume 三件套）——为 C1/C2 的离线包下载做准备；
  - Provider 抽象接口（BaseLocationProvider 形态）。

## 四、推荐路线与分阶段实施计划

> **推荐：C1 立即做 → C2 按需做 → C3 顺手做；A 档只做书面评估，不落地。**
> 目标口径：**拜年当天，无论有没有网、无论在哪家，地图都能显示，轨迹都在实时画。**

| 阶段 | 内容 | 验收标准 | 工作量 |
|---|---|---|---|
| P0（本轮即可启动） | C1 瓦片预缓存：Home 周边 bbox 预载 z13~17；缓存 LRU 容量管理；断网兜底验证（飞行模式看地图） | 飞行模式下打开 App，村周边地图完整可见；重启后仍可见 | 1~2 天 |
| P1 | C3-1 定位省电调度（静止降频） | 静止时定位请求量明显下降；移动时恢复 1s 采样 | 1 天 |
| P2（用户确认后） | C2 离线矢量底图 PoC：MapLibre GL Native + 昌乐县 PMTiles（自生成 ~5MB 级别）；与现有轨迹绘制打通 | 飞行模式全功能可用（底图+轨迹+标记）；APK 增量 <15MB | 1~2 周 |
| P3 | C3-2 断点续传下载器（为离线包更新） | 中断后重下进度接续 | 1~2 天 |
| P4（远期可选，仅评估） | A 档 SDK 嵌入 PoC 书面报告：与 P2 方案对比后再决策 | 报告给出体积/耗时/合规/维护四维对比 | 0 天（调研已够） |

**明确不做**：B 档（剥离 drape）。

## 五、合规红线（任何移植都要遵守）

1. 代码 Apache-2.0 → 保留版权与 NOTICE；
2. 若用 OM 的地图数据（.mwm）→ ODbL：**可见位置署名 "Organic Maps Project" + 可点击 organicmaps.app 链接**（About 页 + 地图界面），**禁止去品牌化/白标**；
3. 若走 C2 自生成（OSM + PMTiles）→ 只需 ODbL 对 OSM 数据的署名（© OpenStreetMap contributors），比 A 档约束更轻。

## 六、关键证据索引（细查用）

- 引擎耦合与 GL 版本：libs/drape/CMakeLists.txt、libs/shaders/gl_shaders_preprocessor.py:325
- 轨迹注入 API：libs/drape_frontend/drape_api.hpp（AddLine 不需要 .mwm）
- SDK 入口链路：android/sdk/src/main/cpp/app/organicmaps/sdk/Map.cpp:24 → Framework.cpp:174
- 数据格式容器：libs/coding/files_container.hpp；增量补丁 libs/mwm_diff/diff.cpp
- 省/国体积：data/countries.json；生成耗时 file_generation_order.txt
- 断点续传：libs/platform/http_client.cpp、libs/storage/http_map_files_downloader.cpp:60
- 定位调度：android/app/src/main/java/app/organicmaps/location/LocationHelper.java
- 许可：README.md（Attribution 章节）、DATA_LICENSE.txt、LICENSES/

## 七、调研结论（三句话）

1. **Organic Maps 的答案是"离线优先"，不是"更好的在线瓦片"**——这是 baibai 地图问题反复没解决的根因；
2. **整库移植不划算**（编译链重、APK 40~85MB、数据 98MB/省、格式无文档、白标限制），**剥离渲染库不可行**（15 万行 C++ + 五层耦合）；
3. **最务实的移植 = 借它的架构思想 + 照抄它的工程细节**（离线预载 → 轻量离线引擎 → 省电定位 → 断点续传），用 1/10 成本拿到它 90% 的体验。
