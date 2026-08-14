# baibai（拜拜）· 乡村拜年轨迹复盘玩具

> 🌐 **线上地址：https://chenchen913.github.io/baibai/** （手机浏览器打开，可添加到主屏幕）
> 📱 **安卓版 APK**：`android/dist/app-debug.apk`（本地零工具链 · CI 云端构建）

记录大年初一走村串户的真实轨迹，拜完年回放路线、算出「当日最优」与「飞行最优」，三线动画对比——看看自己今年绕了多少路。年度独立、每年一局、纯本地、自己玩。

## 文档

- 📄 [项目需求文档](./docs/项目需求文档.md)（22 项决策共识）
- 📄 [拷问决策记录](./docs/拷问决策记录.md)（决策树档案）
- 📄 网页版规格：[M1](./docs/M1_规格.md) · [M2](./docs/M2_规格.md) · [M3](./docs/M3_规格.md) · [M4](./docs/M4_规格.md) · [M5](./docs/M5_规格.md)
- 🎨 [UI 设计提示词](./docs/UI设计提示词.md)（交给 UI 设计 AI 的完整需求文档）
- 📱 **Android 原生版（代码完成，待真机验收）**：[迁移 SPEC](./android/SPEC.md) · [数据格式契约](./android/数据格式.md) · [防杀与后台定位](./android/防杀与后台定位.md) · [开发环境与流程](./android/开发环境与流程.md) · [真机验收清单](./android/真机验收清单.md) · [高德接入指引](./android/高德接入指引.md)

## 技术栈（网页版）

| 层 | 选型 |
|---|---|
| 语言/构建 | TypeScript（严格模式）+ Vite；**无框架、零运行时依赖** |
| 测试 | vitest（111 项单测）+ jsdom（UI 冒烟）+ fake-indexeddb |
| 定位 | 浏览器 Geolocation API（手机自带 GPS/北斗；无高精度差分设备） |
| 存储 | IndexedDB（活跃检查点 / 历史会话 / 今年清单）+ JSON 全量导入导出 |
| 可视化 | **Leaflet + OpenStreetMap 实时地图** + 自绘 SVG/CSS 动画 |
| 离线 | 手写 Service Worker（运行时缓存）+ Web App Manifest + 程序化生成的灯笼图标 |
| 算法 | haversine 球面距离 · 分量中位数 · 滑动平均 + Douglas-Peucker 抽稀 · **Held-Karp 精确解 TSP**（n≤16，超出用贪心+2-opt）· 弧长重采样/折线插值 |

## 运行与启动（网页版）

环境要求：**Node.js ≥ 20**（仅本地开发/构建需要；线上访问零依赖，手机直接打开即可）。

```powershell
npm install      # 安装开发依赖
npm run dev      # 本地开发服务器 → http://localhost:5173
npm run build    # 类型检查 + 生产构建（输出 dist/）
npm run preview  # 本地生产预览 → http://localhost:4173
npm test         # vitest 单元测试（111 项）
```

线上访问：**https://chenchen913.github.io/baibai/**（电脑/手机浏览器均可，纯前端，无后端）。

## 部署（GitHub Pages）

- **推送即发布**：push 到 `main` 分支自动触发 `.github/workflows/pages.yml` → `npm ci` → `npm test` → `npm run build` → 上传构建产物 → 部署 Pages；
- Pages 源为 **GitHub Actions（workflow）模式**，无需手动设置；部署地址即仓库 Pages 地址；
- 仓库：`ChenChen913/baibai`（公开，Apache-2.0）；
- 备注：本仓库已配置 git 代理（`http.proxy = 127.0.0.1:10810`，国内网络推送需要；`git push` 可直接使用）。

## 许可证

[Apache-2.0](./LICENSE)（个人项目，欢迎 fork 与改进）。

## 更新记录

<details>
<summary>📜 点击展开更新记录（按时间倒序）</summary>

### Android 原生版（2026-08-13 ~ 14）

| 日期 | 阶段 | 内容 |
|---|---|---|
| 08-14 | A-M5 打磨互通 | 应用图标（灯笼五档 mipmap）、**JSON 导入/导出**（与网页版互通闭环）、`importAllJson` 契约测试、三份交付文档；CI 115 项全绿 |
| 08-14 | A-M4 跨年便利 | core：`PlanOps`（清单/漏访匹配/套名候选）+ 10 项对照测试；app：清单管理页、回顾页漏访标红与套名芯片、记录页清单入口；CI 114 项 |
| 08-14 | A-M3 优化与压轴 | core：`Tsp`/`Optimize`/`Polyline` + 19 项对照测试，昌乐县 15/20 户仿真数值与网页版逐位一致；app：三线对比页（成绩单四卡+推演动画+压轴 morph）；CI 104 项 |
| 08-14 | A-M2 回放与收拾 | core：`Smooth`/`Track`/`Playback`/`Review`/`Demo` + 39 项对照测试；app：历史页+回顾页（Canvas 回放动画+改名/合并/拆分/跳变剔除）；CI 85 项 |
| 08-14 | A-M1 记录闭环 | Gradle 双模块骨架、`Geo`/`RecorderState`/`JsonStore`/`GpsErrors` 直译 + 38 项对照测试、Compose 记录页、权限/白名单引导、前台服务四层防杀（锁屏持续+START_STICKY+检查点续录）；CI 43 项 |
| 08-13 | 前置任务 | 迁移 SPEC / 数据格式契约 / 防杀与后台定位 / 开发环境与流程（本地零安卓工具链 + 五步软件工程流程） |

### 网页版（2026-08-04 ~ 13）

| 日期 | 阶段 | 内容 |
|---|---|---|
| 08-13 | 修复与升级 | **致命修复**（四视图选择器缺 `#` 导致全按钮失灵）；emoji → SVG 线性图标；礼花/炮仗装饰重绘；按钮状态禁用逻辑；jsdom UI 冒烟测试防回归；测试 111 项 |
| 08-13 | UI 升级 | 记录页重设计为驾驶舱布局（状态大卡/主按钮/工具胶囊条）；全站设计系统升级（玻璃拟态/金色发丝线/多层暖色渐变）；新增 Leaflet + OSM 实时地图（精度圈/轨迹/户标记）与昌乐县模拟村 15/20 户实地仿真测试 |
| 08-13 | 发布 | 上线 GitHub Pages：https://chenchen913.github.io/baibai/ |
| 08-04 | M5 视觉与 PWA | 暖色年味视觉精修（边缘虚化礼花/炮仗）+ PWA 离线（manifest/Service Worker/灯笼图标）；部署工作流就绪 |
| 08-04 | M4 跨年便利 | 清单生成/漏访一对一匹配/套名候选 + IndexedDB plans（DB v2）+ 清单管理页 + 回顾页漏访/套名 + 历年成绩单；测试 102 项 |
| 08-04 | M3 优化与压轴 | Held-Karp 三线优化 + 演示数据生成器 + 三线对比视图（成绩单/推演/压轴 morph）；测试 92 项 |
| 08-04 | M2 回放与收拾 | 平滑/抽稀/分段/SVG 投影算法层 + 回顾页（回放动画/改名/合并/拆分/剔除）+ 历史列表 + 视图路由；测试 72 项 |
| 08-04 | M1 记录闭环 | Vite+TS 脚手架 + geo/state 算法层 + db/gps/ui/main 全流程接线（检查点 10s 落盘/崩溃恢复/震动/Wake Lock/JSON 导出）；测试 35 项 |

</details>
