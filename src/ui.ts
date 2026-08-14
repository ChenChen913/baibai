/** M1 记录页界面：驾驶舱布局（状态卡 + 实时地图 + 大主按钮 + 工具条） */

import type { Mode, SessionData } from './state.js';
import { ICONS } from './icons.js';

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
        <div class="status" id="status">待机</div>
        <div class="meta" id="meta"></div>
        <div class="stat-row">
          <div class="stat"><b id="stat-count">0</b><span>拜访户数</span></div>
          <div class="stat-divider"></div>
          <div class="stat"><b id="stat-time">00:00:00</b><span>本次用时</span></div>
        </div>
      </div>
      <div class="map-card" id="map-card">
        <div class="map-head">
          <span class="map-title">实时地图</span>
          <button id="map-preload" class="map-preload">预载</button>
          <button id="map-toggle" class="map-toggle" aria-label="收起地图">${ICONS.chevron}</button>
        </div>
        <div id="map"></div>
        <div class="map-cap">实时地图 · © OpenStreetMap 贡献者 · 瓦片失败自动换源</div>
      </div>
      <div class="bottom-dock">
        <div class="primary-zone">
          <button id="btn-start" class="primary">开始拜年</button>
          <button id="btn-pause" class="primary">到一户了 · 暂停</button>
          <button id="btn-resume" class="primary">继续出发</button>
          <div class="side-zone">
            <button id="btn-undo" class="ghost">撤销</button>
            <button id="btn-finish" class="ghost danger-text">结束拜年</button>
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
  $('btn-start').addEventListener('click', () => cb.onStart());
  $('btn-pause').addEventListener('click', () => cb.onPause());
  $('btn-resume').addEventListener('click', () => cb.onResume());
  $('btn-undo').addEventListener('click', () => cb.onUndo());
  $('btn-finish').addEventListener('click', () => cb.onFinish());
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

  function render(
    s: SessionData | null,
    elapsedMs: number,
    gpsInfo?: { acc: number | null; waiting: boolean },
  ): void {
    const st = s?.state ?? 'IDLE';
    $('status').textContent = STATE_LABEL[st] ?? st;
    const acc = gpsInfo?.acc ?? null;
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
    // 拜访户数 = 唯一户数（nodes 不含 home；P9：中途回家/回访不虚高）
    $('stat-count').textContent = String(s?.nodes.length ?? 0);
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

  return { render, toast, confirm: (m: string) => window.confirm(m), setFeedbackOn, setDateLabel };
}
