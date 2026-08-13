/** M1 入口：状态机 + GPS + IndexedDB + UI 全流程接线 */

import './style.css';
import { RecorderState } from './state.js';
import { GpsTracker } from './gps.js';
import { mountUi } from './ui.js';
import {
  clearActive,
  exportAllJson,
  loadActive,
  saveActive,
  saveSession,
} from './db.js';
import type { Fix } from './geo.js';

const gps = new GpsTracker();
let recorder: RecorderState | null = null;
let pendingStart = false;
let wakeLock: { release: () => Promise<void> } | null = null;

const now = (): number => Date.now();
const vibrate = (): void => {
  navigator.vibrate?.(50);
};

function flush(): void {
  if (recorder) {
    void saveActive(recorder.checkpoint()).catch((e) => console.warn('[db]', e));
  }
}

function elapsedMs(): number {
  if (!recorder) return 0;
  const s = recorder.snapshot;
  const t0 = s.points[0]?.t ?? s.createdAt;
  return Math.max(0, now() - t0);
}

const ui = mountUi(document.querySelector<HTMLElement>('#app')!, {
  onStart() {
    if (recorder) return;
    pendingStart = true;
    try {
      gps.start(onFix);
      void requestWakeLock();
      ui.toast('正在获取定位…');
    } catch (e) {
      pendingStart = false;
      ui.toast(e instanceof Error ? e.message : '定位启动失败');
    }
  },
  onPause() {
    if (!recorder) return;
    try {
      recorder.pause(gps.recent(3), now());
      vibrate();
      flush();
    } catch (e) {
      ui.toast((e as Error).message);
    }
  },
  onResume() {
    if (!recorder) return;
    recorder.resume(now());
    vibrate();
    flush();
  },
  onUndo() {
    if (!recorder) return;
    if (recorder.undo()) {
      flush();
    } else {
      ui.toast('没有可撤销的操作');
    }
  },
  onFinish() {
    if (!recorder) return;
    const res = recorder.finish(gps.recent(3), now());
    if (!res.ok) {
      const go = ui.confirm(`当前位置距 Home 约 ${Math.round(res.distM)} 米，仍要结束吗？`);
      if (!go) return;
      recorder.finish(gps.recent(3), now(), true);
    }
    complete();
  },
  onMode(m) {
    recorder?.setMode(m, now());
    flush();
  },
  onExport() {
    void doExport();
  },
});

function onFix(f: Fix): void {
  if (pendingStart && !recorder) {
    recorder = new RecorderState();
    try {
      recorder.start([f], now(), f);
    } catch (e) {
      recorder = null;
      ui.toast((e as Error).message);
      return;
    }
    pendingStart = false;
    vibrate();
    flush();
    ui.toast('开始记录！到一户按「暂停」');
    return;
  }
  if (recorder) {
    try {
      recorder.addPoint(f.pos, f.acc, now());
    } catch {
      /* 非 WALKING 状态，忽略 */
    }
  }
}

function complete(): void {
  if (!recorder) return;
  gps.stop();
  void releaseWakeLock();
  void saveSession(recorder.snapshot).catch((e) => console.warn('[db]', e));
  void clearActive();
  vibrate();
  ui.toast('已保存本次拜年 🎉');
}

async function requestWakeLock(): Promise<void> {
  const nav = navigator as Navigator & {
    wakeLock?: { request: (t: 'screen') => Promise<{ release: () => Promise<void> }> };
  };
  try {
    wakeLock = nav.wakeLock ? await nav.wakeLock.request('screen') : null;
  } catch {
    wakeLock = null; // iOS Safari 不支持，部署文档注明手动设自动锁定=永不
  }
}

async function releaseWakeLock(): Promise<void> {
  try {
    await wakeLock?.release();
  } catch {
    /* 忽略 */
  }
  wakeLock = null;
}

async function doExport(): Promise<void> {
  const json = await exportAllJson();
  const blob = new Blob([json], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `baibai-backup-${new Date().toISOString().slice(0, 10)}.json`;
  a.click();
  URL.revokeObjectURL(a.href);
  ui.toast('已导出备份 JSON');
}

async function boot(): Promise<void> {
  try {
    const ck = await loadActive();
    if (ck && !ck.session.finished) {
      const resume = ui.confirm('检测到未完成的拜年记录，继续吗？（取消=放弃）');
      if (resume) {
        recorder = RecorderState.restore(ck);
        if (recorder.state === 'WALKING') gps.start(onFix);
        ui.toast('已恢复未完成的记录');
      } else {
        await clearActive();
      }
    }
  } catch (e) {
    console.warn('[db]', e);
  }
}

setInterval(() => ui.render(recorder?.snapshot ?? null, elapsedMs()), 1000);
setInterval(flush, 10_000); // D22：每 10s 检查点落盘
ui.render(null, 0);
void boot();
