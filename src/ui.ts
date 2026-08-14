/** M1 记录页界面：驾驶舱布局（状态卡 + 实时地图 + 大主按钮 + 工具条） */

import type { Mode, SessionData } from './state.js';
import { ICONS } from './icons.js';
import { launchConfetti } from './confetti.js';

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
  onPreload(): void; // P0：预载周边地图瓦片
}

export interface Ui {
  render(
    s: SessionData | null,
    elapsedMs: number,
    gpsInfo?: { acc: number | null; waiting: boolean },
  ): void;
  toast(msg: string): void;
  confirm(msg: string): boolean;
  setFeedbackOn(on: boolean): void;
  setDateLabel(label: string): void;
  celebrate(): void;
}

const STATE_LABEL: Record<string, string> = {
  IDLE: '待机',
  WALKING: '记录中',
  PAUSED: '在某户',
  FINISHED: '已保存',
};

function fmt(ms: number): string {
  const s = Math.floor(ms / 1000);
  const h = String(Math.floor(s / 3600)).padStart(2, '0');
  const m = String(Math.floor((s % 3600) / 60)).padStart(2, '0');
  const ss = String(s % 60).padStart(2, '0');
  return `${h}:${m}:${ss}`;
}

export function mountUi(root: HTMLElement, cb: UiCallbacks): Ui {
  root.innerHTML = `
    <div class="wrap record">
      <header class="top">
        <div class="brand"><span class="brand-mark">🧧</span>拜拜<span class="brand-sub">拜年轨迹复盘</span></div>
        <button id="btn-date" class="year-chip date-chip" aria-label="选择拜年日期">大年初一</button>
        <button id="btn-feedback" class="sound-toggle" aria-label="关闭提示音与震动">${ICONS.bell}</button>
      </header>
      <div class="stat-card">
        <div class="status-row">
          <div class="status" id="status">待机</div>
          <div id="gps-badge" class="gps-badge"><span class="gps-dot"></span><span id="gps-text">定位就绪</span></div>
        </div>
        <div class="meta" id="meta">从家门口出发，按「开始拜年」</div>
        <div class="stat-row">
          <div class="stat"><b id="stat-count">0</b><span>拜访户数</span></div>
          <div class="stat-divider"></div>
          <div class="stat"><b id="stat-time">00:00:00</b><span>本次用时</span></div>
        </div>
      </div>
      <div class="map-card" id="map-card">
        <div class="map-head">
          <div class="map-title-wrap">
            <span class="map-title">实时地图</span>
            <span id="map-mode-tag" class="map-mode-tag">街道</span>
          </div>
          <div class="map-actions">
            <button id="map-preload" class="map-preload">预载周边</button>
            <button id="map-toggle" class="map-toggle" aria-label="收起地图">${ICONS.chevron}</button>
          </div>
        </div>
        <div id="map"></div>
        <div class="map-cap">实时地图 · © OpenStreetMap / 高德 · 瓦片失败自动换源</div>
      </div>
      <div class="bottom-dock">
        <div class="primary-zone">
          <button id="btn-start" class="primary"><span class="btn-glow"></span>开始拜年</button>
          <button id="btn-pause" class="primary"><span class="btn-glow"></span>到一户了 · 暂停</button>
          <button id="btn-resume" class="primary"><span class="btn-glow"></span>继续出发</button>
          <div class="side-zone">
            <button id="btn-undo" class="ghost">撤销</button>
            <button id="btn-finish" class="ghost danger-text" title="点击或长按结束本次拜年"><span class="finish-progress"></span>结束拜年</button>
            <button id="btn-export" class="ghost">导出数据</button>
          </div>
        </div>
        <div class="toolbar">
          <button id="btn-walk" class="ghost">${ICONS.walk}<span>走路</span></button>
          <button id="btn-bike" class="ghost">${ICONS.bike}<span>骑车</span></button>
          <button id="btn-plan" class="ghost">${ICONS.plan}<span>清单</span></button>
          <button id="btn-history" class="ghost">${ICONS.history}<span>历史</span></button>
        </div>
      </div>
    </div>
    <div id="toast"></div>
  `;

  const $ = (id: string): HTMLElement => root.querySelector<HTMLElement>('#' + id)!;
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
  // 保留直接 click 以兼容桌面与单元测试
  finishBtn.addEventListener('click', (e) => {
    if (e.detail === 0) {
      // 键盘或程序化模拟点击
      cb.onFinish();
    } else {
      // 正常鼠标短点：友好弹窗确认
      cb.onFinish();
    }
  });

  $('btn-export').addEventListener('click', () => cb.onExport());
  $('btn-walk').addEventListener('click', () => cb.onMode('walk'));
  $('btn-bike').addEventListener('click', () => cb.onMode('bike'));
  $('btn-plan').addEventListener('click', () => cb.onPlan());
  $('btn-history').addEventListener('click', () => cb.onHistory());
  $('btn-feedback').addEventListener('click', () => cb.onFeedbackToggle());
  $('btn-date').addEventListener('click', () => cb.onDatePick());
  $('map-preload').addEventListener('click', () => cb.onPreload());

  function setFeedbackOn(on: boolean): void {
    const btn = $('btn-feedback');
    btn.classList.toggle('off', !on);
    btn.setAttribute('aria-label', on ? '关闭提示音与震动' : '开启提示音与震动');
  }

  // 地图折叠/展开（P5）：折叠为 40dp 标题条，180ms 高度过渡；展开后通知地图重算尺寸
  let mapOpen = true;
  $('map-toggle').addEventListener('click', () => {
    mapOpen = !mapOpen;
    $('map-card').classList.toggle('collapsed', !mapOpen);
    const btn = $('map-toggle');
    btn.classList.toggle('open', mapOpen);
    btn.setAttribute('aria-label', mapOpen ? '收起地图' : '展开地图');
    if (mapOpen) {
      window.setTimeout(() => window.dispatchEvent(new CustomEvent('baibai-map-resize')), 200);
    }
  });

  let toastTimer: number | undefined;

  function toast(msg: string): void {
    const t = $('toast');
    t.textContent = msg;
    t.classList.add('show');
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => t.classList.remove('show'), 2400);
  }

  let lastNodeCount = 0;

  function render(
    s: SessionData | null,
    elapsedMs: number,
    gpsInfo?: { acc: number | null; waiting: boolean },
  ): void {
    const st = s?.state ?? 'IDLE';
    $('status').textContent = STATE_LABEL[st] ?? st;
    const acc = gpsInfo?.acc ?? null;
    const gpsBadge = $('gps-badge');
    const gpsText = $('gps-text');

    if (acc !== null) {
      const roundedAcc = Math.round(acc);
      if (acc <= 10) {
        gpsBadge.className = 'gps-badge good';
        gpsText.textContent = `GPS ±${roundedAcc}m`;
      } else if (acc <= 30) {
        gpsBadge.className = 'gps-badge fair';
        gpsText.textContent = `GPS ±${roundedAcc}m`;
      } else {
        gpsBadge.className = 'gps-badge weak';
        gpsText.textContent = `网络定位 ±${roundedAcc}m`;
      }
    } else if (gpsInfo?.waiting) {
      gpsBadge.className = 'gps-badge weak';
      gpsText.textContent = '搜星中…';
    } else {
      gpsBadge.className = 'gps-badge ready';
      gpsText.textContent = '就绪';
    }

    if (s) {
      $('meta').textContent =
        st === 'WALKING' || st === 'PAUSED'
          ? acc !== null
            ? `已定位 · 精度约 ±${Math.round(acc)} 米${acc > 100 ? '（当前为网络粗略定位，手机 GPS 可达 ±3~10 米）' : ''}`
            : '正在获取定位…'
          : st === 'FINISHED'
            ? '本次拜年已保存'
            : '从家门口出发，按「开始拜年」';
    } else {
      $('meta').textContent = gpsInfo?.waiting
        ? '正在获取定位，请允许浏览器定位权限…'
        : '从家门口出发，按「开始拜年」';
    }

    const currentCount = s?.nodes.length ?? 0;
    if (currentCount > 0 && currentCount % 10 === 0 && currentCount !== lastNodeCount) {
      launchConfetti(35);
    }
    lastNodeCount = currentCount;

    // 拜访户数 = 唯一户数（nodes 不含 home；P9：中途回家/回访不虚高）
    $('stat-count').textContent = String(currentCount);
    $('stat-time').textContent = fmt(elapsedMs);
    $('btn-start').style.display = st === 'IDLE' ? '' : 'none';
    $('btn-pause').style.display = st === 'WALKING' ? '' : 'none';
    $('btn-resume').style.display = st === 'PAUSED' ? '' : 'none';
    $('btn-finish').style.display = st === 'WALKING' || st === 'PAUSED' ? '' : 'none';
    $('btn-undo').style.display = st === 'WALKING' || st === 'PAUSED' ? '' : 'none';
    $('btn-export').style.display = st === 'IDLE' || st === 'FINISHED' ? '' : 'none';
    // 出行方式：仅记录中（WALKING/PAUSED）可切换；IDLE/FINISHED 禁用（灰显）
    const canMode = !!s && (st === 'WALKING' || st === 'PAUSED');
    ($('btn-walk') as HTMLButtonElement).disabled = !canMode;
    ($('btn-bike') as HTMLButtonElement).disabled = !canMode;
    $('btn-walk').classList.toggle('active', (s?.currentMode ?? 'walk') === 'walk');
    $('btn-bike').classList.toggle('active', s?.currentMode === 'bike');
  }

  function setDateLabel(label: string): void {
    $('btn-date').textContent = label;
  }

  return {
    render,
    toast,
    confirm: (m: string) => window.confirm(m),
    setFeedbackOn,
    setDateLabel,
    celebrate: () => launchConfetti(50),
  };
}
