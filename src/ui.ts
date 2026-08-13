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
}

export interface Ui {
  render(s: SessionData | null, elapsedMs: number): void;
  toast(msg: string): void;
  confirm(msg: string): boolean;
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
        <div class="year-chip">大年初一</div>
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
      <div class="map-card">
        <div id="map"></div>
        <div class="map-cap">实时地图 · © OpenStreetMap · 断网自动降级示意模式</div>
      </div>
      <div class="primary-zone">
        <button id="btn-start" class="primary">开始拜年</button>
        <button id="btn-pause" class="primary">到一户了 · 暂停</button>
        <button id="btn-resume" class="primary">继续出发</button>
        <button id="btn-finish" class="primary danger">结束拜年</button>
        <div class="side-zone">
          <button id="btn-undo" class="ghost">撤销</button>
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

  let toastTimer: number | undefined;

  function toast(msg: string): void {
    const t = $('toast');
    t.textContent = msg;
    t.classList.add('show');
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => t.classList.remove('show'), 2400);
  }

  function render(s: SessionData | null, elapsedMs: number): void {
    const st = s?.state ?? 'IDLE';
    $('status').textContent = STATE_LABEL[st] ?? st;
    $('meta').textContent = s
      ? st === 'WALKING'
        ? '正在记录轨迹，到一户按「暂停」'
        : st === 'PAUSED'
          ? '离开时按「继续出发」'
          : '本次拜年已保存'
      : '从家门口出发，按「开始拜年」';
    $('stat-count').textContent = String(s?.visits.length ?? 0);
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

  return { render, toast, confirm: (m: string) => window.confirm(m) };
}
