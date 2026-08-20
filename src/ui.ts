/** M1 记录页界面：全景智驾地图 + 黄金双层底栏 + 顶部悬浮看板 + 实时抽屉 */

import type { Mode, SessionData } from './state.js';
import { ICONS } from './icons.js';
import { launchConfetti } from './confetti.js';
import type { Plan } from './plan.js';

export interface UiCallbacks {
  onStart(): void;
  onPause(): void;
  onResume(): void;
  onUndo(): void;
  onFinish(): void;
  onMode(m: Mode): void;
  onExport(): void;
  onHistory(): void;
  onPlan(): void;
  onFeedbackToggle(): void;
  onDatePick(): void;
  onPreload(): void;
  onRecenter?(): void;
  onFitBounds?(): void;
  onLayerSwitch?(): void;
  onFocusNode?(no: number): void;
  onAddHouse?(): void;
  onImportPlan?(): void;
  onSelectHistory?(id: string): void;
  onDemoHistory?(): void;
  onImportJson?(file: File): void;
}

export interface Ui {
  render(
    s: SessionData | null,
    elapsedMs: number,
    gpsInfo?: { acc: number | null; waiting: boolean },
  ): void;
  setPlan(plan: Plan | null): void;
  setHistorySessions(sessions: SessionData[], statsMap?: Map<string, string>): void;
  openDrawer(type: 'plan' | 'history'): void;
  closeDrawer(type: 'plan' | 'history'): void;
  toast(msg: string): void;
  confirm(msg: string): boolean;
  setFeedbackOn(on: boolean): void;
  setDateLabel(label: string): void;
  celebrate(): void;
}

const STATE_LABEL: Record<string, string> = {
  IDLE: '待机就绪',
  WALKING: '行进记录中',
  PAUSED: '进门拜年中',
  FINISHED: '拜年已完成',
};

function fmt(ms: number): string {
  const s = Math.floor(ms / 1000);
  const h = String(Math.floor(s / 3600)).padStart(2, '0');
  const m = String(Math.floor((s % 3600) / 60)).padStart(2, '0');
  const ss = String(s % 60).padStart(2, '0');
  return `${h}:${m}:${ss}`;
}

const esc = (s: string): string =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

export function mountUi(root: HTMLElement, cb: UiCallbacks): Ui {
  root.innerHTML = `
    <div class="app-viewport">
      <!-- 手机外框拟态 (桌面居中，移动端 100% 满屏) -->
      <div class="phone-mockup">
        <!-- 灵动岛 (顶部美学胶囊) -->
        <div class="island">
          <span class="island-dot"></span>
          <span class="island-text" id="island-text">GPS 定位已锁定</span>
        </div>

        <!-- 1. 底层 100% 满屏地图容器 -->
        <div id="map" class="map-viewport"></div>

        <!-- 2. 顶部悬浮看板 (Top HUD) -->
        <div class="top-hud-container pointer-events-none">
          <div class="pointer-events-auto space-y-2">
            <!-- 顶栏：品牌 + 实时 GPS 状态 + 提示音与日期 -->
            <div class="hud-top-bar">
              <div class="glass-hud hud-brand-chip">
                <div class="brand-badge-icon">${ICONS.fire}</div>
                <span class="brand-title font-cny-serif">拜拜</span>
                <button id="btn-date" class="date-chip-btn" aria-label="选择拜年日期">大年初一</button>
              </div>

              <div class="flex items-center gap-1.5">
                <button id="btn-feedback" class="glass-hud hud-icon-btn" aria-label="提示音与震动切换">
                  ${ICONS.bell}
                </button>
                <div id="gps-badge" class="glass-hud gps-pill">
                  <span class="gps-dot"></span>
                  <span id="gps-text">±3m 良好</span>
                </div>
              </div>
            </div>

            <!-- 数据聚合卡片 (Glass Dashboard) -->
            <div class="glass-hud hud-dashboard">
              <div class="dashboard-grid">
                <!-- 拜访进度 -->
                <div class="dash-item border-r border-stone-200/80">
                  <div class="dash-val-row">
                    <span id="stat-count" class="dash-val-main text-red-600">0</span>
                    <span id="stat-plan-total" class="dash-val-sub">/0户</span>
                  </div>
                  <div class="dash-label">已拜访</div>
                </div>

                <!-- 记录时间 -->
                <div class="dash-item border-r border-stone-200/80">
                  <span id="stat-time" class="dash-val-main font-mono text-stone-800">00:00:00</span>
                  <div class="dash-label">记录用时</div>
                </div>

                <!-- 距离 -->
                <div class="dash-item">
                  <div class="dash-val-row">
                    <span id="stat-dist" class="dash-val-main text-stone-800">0.0</span>
                    <span class="dash-val-sub">km</span>
                  </div>
                  <div class="dash-label">实走距离</div>
                </div>
              </div>

              <!-- 动态指示小条 (Sub Banner) -->
              <div class="hud-sub-banner">
                <div class="banner-status-wrap">
                  <span class="status-pulse-dot" id="banner-dot"></span>
                  <span id="banner-text" class="banner-text">从家门口出发，按「开始拜年」</span>
                </div>
                <button id="btn-banner-plan" class="banner-link-btn">
                  <span>清单对表</span> <span class="text-[9px]">›</span>
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- 3. 地图悬浮工具栏 (右侧快捷操作) -->
        <div class="map-floating-tools">
          <button id="map-recenter" title="定位回中" class="glass-hud map-tool-btn" aria-label="定位回中">
            ${ICONS.crosshair}
          </button>
          <button id="map-fit" title="总览全路线" class="glass-hud map-tool-btn" aria-label="总览全路线">
            ${ICONS.route}
          </button>
          <button id="map-layer-toggle" title="切换卫星/矢量地图" class="glass-hud map-tool-btn" aria-label="切换图层">
            ${ICONS.layers}
          </button>
          <button id="map-preload" title="离线预载周边地图" class="glass-hud map-tool-btn" aria-label="预载周边">
            ${ICONS.download}
          </button>
        </div>

        <!-- 4. 底部高级控制座舱 (Bottom Cockpit) -->
        <div class="bottom-cockpit-container pointer-events-none">
          <div class="pointer-events-auto space-y-2">
            
            <!-- ① 核心操作三连键 (Tier 1: Major Actions) -->
            <div class="tier1-actions">
              <!-- 结束拜年 (左侧辅助键) -->
              <button id="btn-finish" class="btn-sub-action h-13 px-3 rounded-2xl flex flex-col items-center justify-center text-stone-700 hover:text-red-600 transition" title="结束本次拜年">
                <div class="text-sm text-red-600">${ICONS.flag}</div>
                <span class="text-[10px] font-black mt-0.5 text-stone-700">结束复盘</span>
              </button>

              <!-- 核心大主按键 (中间主行动点，触控主热区) -->
              <button id="btn-start" class="btn-cny-cta flex-1 h-13 rounded-2xl text-white font-black flex items-center justify-center gap-2.5 shadow-xl">
                <div class="w-7 h-7 rounded-full bg-white/20 flex items-center justify-center text-sm">
                  ${ICONS.play}
                </div>
                <div class="text-left">
                  <div class="text-base font-black leading-tight tracking-wide">开始拜年记录</div>
                  <div class="text-[9px] opacity-85 font-normal">开启 GPS 轨迹与到户记录</div>
                </div>
              </button>

              <button id="btn-pause" class="btn-cny-cta flex-1 h-13 rounded-2xl text-white font-black flex items-center justify-center gap-2.5 shadow-xl" style="display:none">
                <div class="w-7 h-7 rounded-full bg-white/20 flex items-center justify-center text-sm">
                  ${ICONS.house}
                </div>
                <div class="text-left">
                  <div class="text-base font-black leading-tight tracking-wide">到一户了 · 记录停留</div>
                  <div class="text-[9px] opacity-85 font-normal">点击自动标记到访并暂停</div>
                </div>
              </button>

              <button id="btn-resume" class="btn-cny-resume flex-1 h-13 rounded-2xl text-white font-black flex items-center justify-center gap-2.5 shadow-xl" style="display:none">
                <div class="w-7 h-7 rounded-full bg-white/20 flex items-center justify-center text-sm">
                  ${ICONS.arrowRight}
                </div>
                <div class="text-left">
                  <div class="text-base font-black leading-tight tracking-wide">拜完了 · 继续出发</div>
                  <div class="text-[9px] opacity-85 font-normal">恢复 GPS 轨迹录制</div>
                </div>
              </button>

              <!-- 撤销上点 (右侧辅助键) -->
              <button id="btn-undo" class="btn-sub-action h-13 px-3 rounded-2xl flex flex-col items-center justify-center text-stone-700 hover:text-stone-900 transition" title="撤销上一个打点">
                <div class="text-sm text-amber-700">${ICONS.undo}</div>
                <span class="text-[10px] font-black mt-0.5 text-stone-700">撤销上点</span>
              </button>

              <!-- 导出数据 (IDLE 态下备用键) -->
              <button id="btn-export" class="btn-sub-action h-13 px-3 rounded-2xl flex flex-col items-center justify-center text-stone-700 transition" style="display:none" title="导出数据备份">
                <div class="text-sm text-stone-700">${ICONS.download}</div>
                <span class="text-[10px] font-black mt-0.5 text-stone-700">备份</span>
              </button>
            </div>

            <!-- ② 底部常驻功能导航坞 (Tier 2: Bottom Navigation Dock) -->
            <div class="glass-dock bottom-dock-bar">
              <!-- 走路模式 -->
              <button id="btn-walk" class="tab-item tab-active">
                <span class="tab-icon">${ICONS.walk}</span>
                <span class="tab-text">走路</span>
              </button>

              <!-- 骑车模式 -->
              <button id="btn-bike" class="tab-item text-stone-500">
                <span class="tab-icon">${ICONS.bike}</span>
                <span class="tab-text">骑车</span>
              </button>

              <!-- 清单按钮 -->
              <button id="btn-plan" class="tab-item text-stone-700 relative">
                <div class="relative inline-flex">
                  <span class="tab-icon text-amber-600">${ICONS.plan}</span>
                  <span id="plan-badge-dot" class="badge-dot-red" style="display:none"></span>
                </div>
                <span class="tab-text font-black">拜年清单</span>
              </button>

              <!-- 历史按钮 -->
              <button id="btn-history" class="tab-item text-stone-700">
                <span class="tab-icon text-stone-600">${ICONS.history}</span>
                <span class="tab-text font-black">往年历史</span>
              </button>
            </div>

          </div>
        </div>

        <!-- ==================== 抽屉 1：拜年清单 (Plan Drawer) ==================== -->
        <div id="drawer-plan" class="drawer-container pointer-events-none opacity-0">
          <div id="drawer-plan-mask" class="drawer-backdrop pointer-events-auto"></div>
          <div class="drawer-panel pointer-events-auto">
            <div class="drawer-handle"></div>

            <div class="drawer-header">
              <div>
                <h3 class="drawer-title font-cny-serif">
                  <span>📋 新春拜年路线清单</span>
                  <span id="drawer-plan-badge" class="drawer-badge-red">0 已拜</span>
                </h3>
                <p class="drawer-subtitle">按规划顺序拜访，到户自动对表打勾</p>
              </div>
              <button id="btn-close-plan" class="drawer-close-btn" aria-label="关闭清单">
                ${ICONS.xmark}
              </button>
            </div>

            <!-- 清单列表项 -->
            <div id="drawer-plan-list" class="drawer-list-body">
              <div class="p-3 text-center text-xs text-stone-400">正在读取今年清单…</div>
            </div>

            <!-- 底部新增户数快捷按钮 -->
            <div class="drawer-footer-actions">
              <button id="btn-plan-add" class="btn-primary-action">
                ${ICONS.plus} 手动添加新拜访户
              </button>
              <button id="btn-plan-import" class="btn-secondary-action">
                📥 导入去年
              </button>
              <button id="btn-plan-return" class="btn-secondary-action">
                返回地图
              </button>
            </div>
          </div>
        </div>

        <!-- ==================== 抽屉 2：往年历史 (History Drawer) ==================== -->
        <div id="drawer-history" class="drawer-container pointer-events-none opacity-0">
          <div id="drawer-history-mask" class="drawer-backdrop pointer-events-auto"></div>
          <div class="drawer-panel pointer-events-auto">
            <div class="drawer-handle"></div>

            <div class="drawer-header">
              <div>
                <h3 class="drawer-title font-cny-serif">
                  <span>📜 历年拜年记录与复盘</span>
                </h3>
                <p class="drawer-subtitle">查看往年轨迹回放、用时分析与路线优化收益</p>
              </div>
              <button id="btn-close-history" class="drawer-close-btn" aria-label="关闭历史">
                ${ICONS.xmark}
              </button>
            </div>

            <!-- 顶部操作行 -->
            <div class="history-quick-tools">
              <button id="btn-history-export" class="history-tool-btn">
                ${ICONS.download} 导出 JSON
              </button>
              <button id="btn-history-import" class="history-tool-btn">
                ${ICONS.upload} 导入 JSON
              </button>
              <button id="btn-history-demo" class="history-tool-btn font-bold text-amber-700">
                ✨ 生成演示数据
              </button>
              <input id="history-file-input" type="file" accept="application/json,.json" style="display:none"/>
            </div>

            <!-- 历年成绩单与趋势图 -->
            <div id="drawer-history-summary" class="history-summary-box"></div>

            <!-- 历史记录卡片列表 -->
            <div id="drawer-history-list" class="drawer-list-body">
              <div class="p-3 text-center text-xs text-stone-400">正在读取历史记录…</div>
            </div>

            <div class="drawer-footer-actions">
              <button id="btn-history-return" class="w-full py-2.5 rounded-xl bg-stone-200 text-stone-700 font-bold text-xs">
                返回实时地图
              </button>
            </div>
          </div>
        </div>

      </div>
    </div>
    <div id="toast"></div>
  `;

  const $ = (id: string): HTMLElement => root.querySelector<HTMLElement>('#' + id)!;

  // 绑定主行动按键
  $('btn-start').addEventListener('click', () => {
    launchConfetti(25);
    cb.onStart();
  });
  $('btn-pause').addEventListener('click', () => cb.onPause());
  $('btn-resume').addEventListener('click', () => cb.onResume());
  $('btn-undo').addEventListener('click', () => cb.onUndo());

  // 结束拜年：防误触长按保护与即时响应兼备
  const finishBtn = $('btn-finish') as HTMLButtonElement;
  let finishHoldTimer: number | undefined;
  let finishHolding = false;

  const startHold = (): void => {
    finishHolding = true;
    finishBtn.classList.add('holding');
    window.clearTimeout(finishHoldTimer);
    finishHoldTimer = window.setTimeout(() => {
      if (finishHolding) {
        finishHolding = false;
        finishBtn.classList.remove('holding');
        launchConfetti(45);
        cb.onFinish();
      }
    }, 1200);
  };

  const cancelHold = (): void => {
    if (finishHolding) {
      finishHolding = false;
      finishBtn.classList.remove('holding');
      window.clearTimeout(finishHoldTimer);
    }
  };

  finishBtn.addEventListener('pointerdown', startHold);
  finishBtn.addEventListener('pointerup', cancelHold);
  finishBtn.addEventListener('pointercancel', cancelHold);
  finishBtn.addEventListener('mouseleave', cancelHold);
  finishBtn.addEventListener('click', () => cb.onFinish());

  // 工具栏与操作栏
  $('btn-export').addEventListener('click', () => cb.onExport());
  $('btn-walk').addEventListener('click', () => cb.onMode('walk'));
  $('btn-bike').addEventListener('click', () => cb.onMode('bike'));
  $('btn-plan').addEventListener('click', () => {
    openDrawer('plan');
    cb.onPlan();
  });
  $('btn-history').addEventListener('click', () => {
    openDrawer('history');
    cb.onHistory();
  });
  $('btn-banner-plan').addEventListener('click', () => {
    openDrawer('plan');
    cb.onPlan();
  });
  $('btn-feedback').addEventListener('click', () => cb.onFeedbackToggle());
  $('btn-date').addEventListener('click', () => cb.onDatePick());
  $('map-preload').addEventListener('click', () => cb.onPreload());

  // 地图悬浮工具按钮
  $('map-recenter').addEventListener('click', () => cb.onRecenter?.());
  $('map-fit').addEventListener('click', () => cb.onFitBounds?.());
  $('map-layer-toggle').addEventListener('click', () => cb.onLayerSwitch?.());

  // 抽屉展开/关闭
  function openDrawer(type: 'plan' | 'history'): void {
    const drawer = $('drawer-' + type);
    if (!drawer) return;
    drawer.classList.remove('pointer-events-none', 'opacity-0');
    const panel = drawer.querySelector<HTMLElement>('.drawer-panel');
    if (panel) panel.classList.add('drawer-open');
  }

  function closeDrawer(type: 'plan' | 'history'): void {
    const drawer = $('drawer-' + type);
    if (!drawer) return;
    const panel = drawer.querySelector<HTMLElement>('.drawer-panel');
    if (panel) panel.classList.remove('drawer-open');
    window.setTimeout(() => {
      drawer.classList.add('pointer-events-none', 'opacity-0');
    }, 280);
  }

  $('btn-close-plan').addEventListener('click', () => closeDrawer('plan'));
  $('drawer-plan-mask').addEventListener('click', () => closeDrawer('plan'));
  $('btn-plan-return').addEventListener('click', () => closeDrawer('plan'));

  $('btn-close-history').addEventListener('click', () => closeDrawer('history'));
  $('drawer-history-mask').addEventListener('click', () => closeDrawer('history'));
  $('btn-history-return').addEventListener('click', () => closeDrawer('history'));

  // 抽屉内事件
  $('btn-plan-add').addEventListener('click', () => cb.onAddHouse?.());
  $('btn-plan-import').addEventListener('click', () => cb.onImportPlan?.());
  $('btn-history-export').addEventListener('click', () => cb.onExport());
  $('btn-history-demo').addEventListener('click', () => {
    closeDrawer('history');
    cb.onDemoHistory?.();
  });

  const historyFileInput = $('history-file-input') as HTMLInputElement;
  $('btn-history-import').addEventListener('click', () => historyFileInput.click());
  historyFileInput.addEventListener('change', () => {
    const file = historyFileInput.files?.[0];
    if (file) {
      cb.onImportJson?.(file);
      historyFileInput.value = '';
    }
  });

  function setFeedbackOn(on: boolean): void {
    const btn = $('btn-feedback');
    btn.innerHTML = on ? ICONS.bell : ICONS.bellOff;
    btn.classList.toggle('off', !on);
    btn.setAttribute('aria-label', on ? '关闭提示音与震动' : '开启提示音与震动');
  }

  let toastTimer: number | undefined;
  function toast(msg: string): void {
    const t = $('toast');
    t.textContent = msg;
    t.classList.add('show');
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => t.classList.remove('show'), 2400);
  }

  let currentPlan: Plan | null = null;
  let lastNodeCount = 0;

  function render(
    s: SessionData | null,
    elapsedMs: number,
    gpsInfo?: { acc: number | null; waiting: boolean },
  ): void {
    const st = s?.state ?? 'IDLE';
    const acc = gpsInfo?.acc ?? null;
    const gpsBadge = $('gps-badge');
    const gpsText = $('gps-text');
    const islandText = $('island-text');

    if (acc !== null) {
      const roundedAcc = Math.round(acc);
      if (acc <= 10) {
        gpsBadge.className = 'glass-hud gps-pill good';
        gpsText.textContent = `±${roundedAcc}m 良好`;
        islandText.textContent = `GPS 锁定 · 精度 ±${roundedAcc}m`;
      } else if (acc <= 30) {
        gpsBadge.className = 'glass-hud gps-pill fair';
        gpsText.textContent = `±${roundedAcc}m 一般`;
        islandText.textContent = `GPS 良好 · 精度 ±${roundedAcc}m`;
      } else {
        gpsBadge.className = 'glass-hud gps-pill weak';
        gpsText.textContent = `网络 ±${roundedAcc}m`;
        islandText.textContent = `网络粗略定位 ±${roundedAcc}m`;
      }
    } else if (gpsInfo?.waiting) {
      gpsBadge.className = 'glass-hud gps-pill weak';
      gpsText.textContent = '搜星中…';
      islandText.textContent = '正在获取卫星定位…';
    } else {
      gpsBadge.className = 'glass-hud gps-pill ready';
      gpsText.textContent = '定位就绪';
      islandText.textContent = '待机就绪 · GPS 信号极佳';
    }

    const currentCount = s?.nodes.length ?? 0;
    if (currentCount > 0 && currentCount % 10 === 0 && currentCount !== lastNodeCount) {
      launchConfetti(35);
    }
    lastNodeCount = currentCount;

    // 拜访户数与计划数
    $('stat-count').textContent = String(currentCount);
    const planTotal = currentPlan?.items.length ?? 0;
    $('stat-plan-total').textContent = planTotal > 0 ? `/${planTotal}户` : '户';
    $('stat-time').textContent = fmt(elapsedMs);

    // 计算实走总距离 (km)
    let distKm = 0;
    if (s && s.points.length > 1) {
      let dM = 0;
      for (let i = 1; i < s.points.length; i++) {
        const p1 = s.points[i - 1].pos;
        const p2 = s.points[i].pos;
        const rad = Math.PI / 180;
        const dLat = (p2.lat - p1.lat) * rad;
        const dLng = (p2.lng - p1.lng) * rad;
        const a =
          Math.sin(dLat / 2) ** 2 +
          Math.cos(p1.lat * rad) * Math.cos(p2.lat * rad) * Math.sin(dLng / 2) ** 2;
        dM += 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
      }
      distKm = dM / 1000;
    }
    $('stat-dist').textContent = distKm.toFixed(2); // R9：0.1km 粒度太粗（走几十米看不出变化），改 10m 粒度

    // 动态 Banner 指示文案
    const bannerText = $('banner-text');
    const bannerDot = $('banner-dot');

    if (st === 'IDLE') {
      bannerText.textContent = gpsInfo?.waiting
        ? '正在获取定位中，请稍候…'
        : '待机就绪 · 点击开始拜年记录';
      bannerDot.className = 'status-pulse-dot bg-amber-500';
    } else if (st === 'WALKING') {
      const nextNo = currentCount + 1;
      const nextPlanItem = currentPlan?.items[currentCount];
      const targetName = nextPlanItem?.name ? `(${nextPlanItem.name})` : '';
      bannerText.textContent = `正在前往第 ${nextNo} 户 ${targetName} 中…`;
      bannerDot.className = 'status-pulse-dot bg-emerald-500 animate-pulse';
    } else if (st === 'PAUSED') {
      const curNo = currentCount;
      const curPlanItem = currentPlan?.items[currentCount - 1];
      const curName = curPlanItem?.name ? `(${curPlanItem.name})` : '';
      bannerText.textContent = `正在第 ${curNo} 户 ${curName} 停留喝茶/拜年中…`;
      bannerDot.className = 'status-pulse-dot bg-red-600 animate-pulse';
    } else {
      bannerText.textContent = '本次拜年已完成保存，可复盘对比路线！';
      bannerDot.className = 'status-pulse-dot bg-stone-400';
    }

    // 按钮显隐切换
    $('btn-start').style.display = st === 'IDLE' ? 'flex' : 'none';
    $('btn-pause').style.display = st === 'WALKING' ? 'flex' : 'none';
    $('btn-resume').style.display = st === 'PAUSED' ? 'flex' : 'none';
    $('btn-finish').style.display = st === 'WALKING' || st === 'PAUSED' ? 'flex' : 'none';
    $('btn-undo').style.display = st === 'WALKING' || st === 'PAUSED' ? 'flex' : 'none';
    $('btn-export').style.display = st === 'IDLE' || st === 'FINISHED' ? 'flex' : 'none';

    // 出行方式切换
    const canMode = !!s && (st === 'WALKING' || st === 'PAUSED');
    ($('btn-walk') as HTMLButtonElement).disabled = !canMode;
    ($('btn-bike') as HTMLButtonElement).disabled = !canMode;
    $('btn-walk').className = (s?.currentMode ?? 'walk') === 'walk'
      ? 'tab-item tab-active'
      : 'tab-item text-stone-500';
    $('btn-bike').className = s?.currentMode === 'bike'
      ? 'tab-item tab-active'
      : 'tab-item text-stone-500';

    renderPlanDrawer(s);
  }

  function setPlan(p: Plan | null): void {
    currentPlan = p;
  }

  function renderPlanDrawer(s: SessionData | null): void {
    const listEl = $('drawer-plan-list');
    const badgeEl = $('drawer-plan-badge');
    const badgeDot = $('plan-badge-dot');

    const visitedCount = s?.nodes.length ?? 0;
    const planItems = currentPlan?.items ?? [];
    const totalCount = planItems.length;

    badgeEl.textContent = `${visitedCount}/${totalCount > 0 ? totalCount : visitedCount} 已拜`;
    badgeDot.style.display = totalCount > visitedCount ? 'block' : 'none';

    if (totalCount === 0 && visitedCount === 0) {
      listEl.innerHTML = `
        <div class="p-4 text-center space-y-2">
          <div class="text-amber-600 text-2xl">📋</div>
          <div class="text-xs font-bold text-stone-700">暂无拜访清单</div>
          <div class="text-[11px] text-stone-400">可点击下方「导入去年」或「手动添加新拜访户」</div>
        </div>
      `;
      return;
    }

    let html = '';

    // 起点卡片
    html += `
      <div class="p-2.5 rounded-xl bg-amber-50 border border-amber-200 flex items-center justify-between shadow-xs">
        <div class="flex items-center gap-2">
          <div class="w-6 h-6 rounded-full bg-amber-500 text-white flex items-center justify-center font-bold text-[10px]">
            ${ICONS.house}
          </div>
          <div>
            <div class="font-bold text-xs text-stone-800">起点：自家庭院</div>
            <div class="text-[10px] text-stone-400">大年初一 出发点</div>
          </div>
        </div>
        <span class="text-[10px] font-bold text-amber-700 bg-amber-100 px-2 py-0.5 rounded">出发点</span>
      </div>
    `;

    // 已拜访各户 (nodes)
    if (s && s.nodes.length > 0) {
      s.nodes.forEach((n, idx) => {
        const isCurrent = s.state === 'PAUSED' && idx === s.nodes.length - 1;
        const name = n.name || `第 ${n.autoNo} 户`;

        if (isCurrent) {
          html += `
            <div class="p-2.5 rounded-xl bg-red-50 border border-red-200 flex items-center justify-between shadow-xs">
              <div class="flex items-center gap-2">
                <div class="w-6 h-6 rounded-full bg-red-600 text-white flex items-center justify-center font-bold text-[10px] animate-pulse">
                  ${n.autoNo}
                </div>
                <div>
                  <div class="font-bold text-xs text-red-700">${n.autoNo}. ${esc(name)} (当前停留点)</div>
                  <div class="text-[10px] text-stone-500">正在喝茶拜年中…</div>
                </div>
              </div>
              <span class="text-[10px] font-bold text-red-700 bg-red-100 px-2 py-0.5 rounded">当前位置</span>
            </div>
          `;
        } else {
          html += `
            <div class="p-2.5 rounded-xl bg-white border border-stone-200/80 flex items-center justify-between shadow-xs">
              <div class="flex items-center gap-2">
                <div class="w-6 h-6 rounded-full bg-emerald-500 text-white flex items-center justify-center font-bold text-[10px]">
                  ${ICONS.check}
                </div>
                <div>
                  <div class="font-bold text-xs text-stone-500 line-through">${n.autoNo}. ${esc(name)}</div>
                  <div class="text-[10px] text-stone-400">已顺利完成拜访</div>
                </div>
              </div>
              <span class="text-[10px] font-bold text-emerald-700 bg-emerald-50 px-2 py-0.5 rounded">已拜访</span>
            </div>
          `;
        }
      });
    }

    // 未拜访清单项
    if (planItems.length > visitedCount) {
      for (let i = visitedCount; i < planItems.length; i++) {
        const item = planItems[i];
        const num = i + 1;
        html += `
          <div class="p-2.5 rounded-xl bg-white border border-stone-200 flex items-center justify-between shadow-xs">
            <div class="flex items-center gap-2">
              <div class="w-6 h-6 rounded-full bg-stone-200 text-stone-700 flex items-center justify-center font-bold text-[10px]">
                ${num}
              </div>
              <div>
                <div class="font-bold text-xs text-stone-800">${num}. ${esc(item.name)}</div>
                <div class="text-[10px] text-stone-400">待拜访 · 路线已优化规划</div>
              </div>
            </div>
            <button data-focus-node="${num}" class="text-[10px] font-bold text-red-600 border border-red-200 bg-red-50 hover:bg-red-100 px-2 py-1 rounded transition">
              定位导航
            </button>
          </div>
        `;
      }
    }

    listEl.innerHTML = html;

    listEl.querySelectorAll<HTMLElement>('[data-focus-node]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const no = Number(btn.dataset.focusNode);
        closeDrawer('plan');
        cb.onFocusNode?.(no);
      });
    });
  }

  function setHistorySessions(sessions: SessionData[], statsMap?: Map<string, string>): void {
    const listEl = $('drawer-history-list');
    const summaryEl = $('drawer-history-summary');

    if (sessions.length === 0) {
      summaryEl.innerHTML = '';
      listEl.innerHTML = `
        <div class="p-6 text-center space-y-2">
          <div class="text-2xl">🧧</div>
          <div class="text-xs font-bold text-stone-700">暂无往年拜年记录</div>
          <div class="text-[11px] text-stone-400">初一拜年结束后自动保存，也可点上方「生成演示数据」体验</div>
        </div>
      `;
      return;
    }

    // 渲染历史记录卡片
    listEl.innerHTML = sessions.map((s) => {
      const statText = statsMap?.get(s.id) || `${s.nodes.length} 户`;
      return `
        <div class="p-3.5 rounded-2xl bg-white border border-stone-200/90 shadow-xs space-y-2.5">
          <div class="flex justify-between items-center">
            <div class="font-bold text-stone-800 text-xs">${esc(s.date)} · 正月初一</div>
            <span class="text-[10px] font-bold text-red-700 bg-red-50 px-2 py-0.5 rounded">拜访 ${s.nodes.length} 户</span>
          </div>
          <div class="text-xs text-stone-600 bg-stone-50 p-2 rounded-xl">
            ${esc(statText)}
          </div>
          <div class="flex justify-end gap-2 pt-1">
            <button data-history-id="${s.id}" class="px-3 py-1.5 rounded-lg bg-red-600 text-white font-bold text-[11px] shadow-xs hover:bg-red-700 active:scale-95 transition flex items-center gap-1">
              ${ICONS.play} 轨迹回放与三线复盘
            </button>
          </div>
        </div>
      `;
    }).join('');

    listEl.querySelectorAll<HTMLElement>('[data-history-id]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const id = btn.dataset.historyId!;
        closeDrawer('history');
        cb.onSelectHistory?.(id);
      });
    });

    // 历年绕路趋势
    summaryEl.innerHTML = `
      <div class="p-2.5 rounded-xl bg-amber-50/70 border border-amber-200/80 flex items-center justify-between text-xs text-amber-900">
        <span class="font-bold">📊 已累计记录 ${sessions.length} 年拜年轨迹</span>
        <span class="text-[10px] text-amber-700 bg-amber-100 px-2 py-0.5 rounded font-bold">算法 Held-Karp 精确解</span>
      </div>
    `;
  }

  function setDateLabel(label: string): void {
    $('btn-date').textContent = label;
  }

  return {
    render,
    setPlan,
    setHistorySessions,
    openDrawer,
    closeDrawer,
    toast,
    confirm: (m: string) => window.confirm(m),
    setFeedbackOn,
    setDateLabel,
    celebrate: () => launchConfetti(50),
  };
}

