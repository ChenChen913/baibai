/** M1 界面：状态大字 + 大按钮 + 出行方式切换（暖色基础版，视觉精修在 M5） */

import type { Mode, SessionData } from './state.js';

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
  WALKING: '🚶 记录中',
  PAUSED: '🏠 在某户',
  FINISHED: '✅ 已保存',
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
    <div class="wrap">
      <header><h1>🧧 拜拜 · 拜年轨迹复盘</h1></header>
      <div class="status" id="status">待机</div>
      <div class="meta" id="meta"></div>
      <div class="buttons">
        <button id="btn-start">开始拜年</button>
        <button id="btn-pause" class="secondary">暂停 · 到一户</button>
        <button id="btn-resume" class="secondary">继续</button>
        <button id="btn-finish">结束拜年</button>
        <button id="btn-undo" class="secondary">撤销</button>
        <button id="btn-export" class="secondary">导出数据</button>
      </div>
      <div class="toolbar">
        <button id="btn-walk" class="secondary">🚶 走路</button>
        <button id="btn-bike" class="secondary">🚲 骑车</button>
        <button id="btn-plan" class="secondary">📋 清单</button>
        <button id="btn-history" class="secondary">📜 历史</button>
      </div>
    </div>
    <div id="toast"></div>
  `;

  const $ = (id: string): HTMLElement => root.querySelector<HTMLElement>(id)!;
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
      ? `已拜访 ${s.visits.length} 户 · ${fmt(elapsedMs)} · ${s.currentMode === 'bike' ? '🚲 骑车' : '🚶 走路'}`
      : '大年初一出发前，先按「开始拜年」';
    $('btn-start').style.display = st === 'IDLE' ? '' : 'none';
    $('btn-pause').style.display = st === 'WALKING' ? '' : 'none';
    $('btn-resume').style.display = st === 'PAUSED' ? '' : 'none';
    $('btn-finish').style.display = st === 'WALKING' || st === 'PAUSED' ? '' : 'none';
    $('btn-undo').style.display = st === 'WALKING' || st === 'PAUSED' ? '' : 'none';
    $('btn-export').style.display = st === 'IDLE' || st === 'FINISHED' ? '' : 'none';
    $('btn-walk').classList.toggle('active', (s?.currentMode ?? 'walk') === 'walk');
    $('btn-bike').classList.toggle('active', s?.currentMode === 'bike');
  }

  return { render, toast, confirm: (m: string) => window.confirm(m) };
}
