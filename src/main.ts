/** M1 入口：状态机 + GPS + IndexedDB + UI 全流程接线（含 记录/历史/回顾 视图路由） */

import './style.css';
import 'leaflet/dist/leaflet.css';
import { RecorderState } from './state.js';
import { GpsTracker, type GpsErrorKind } from './gps.js';
import { mountUi, type Ui } from './ui.js';
import { mountMap, type MapController } from './map-ui.js';
import { mountReviewView } from './review-ui.js';
import { mountOptimizeView } from './optimize-ui.js';
import { mountPlanView } from './plan-ui.js';
import { generateDemoSession } from './demo.js';
import { scorecard, optimizeSession } from './optimize.js';
import type { SessionData } from './state.js';
import {
  clearActive,
  exportAllJson,
  importAllJson,
  listSessions,
  loadActive,
  loadPlan,
  saveActive,
  savePlan,
  saveSession,
} from './db.js';
import type { Fix } from './geo.js';

const app = document.querySelector<HTMLElement>('#app')!;
const gps = new GpsTracker();
let recorder: RecorderState | null = null;
let pendingStart = false;
let wakeLock: { release: () => Promise<void> } | null = null;
let view: 'record' | 'history' | 'review' | 'optimize' | 'plan' = 'record';
let mapCtrl: MapController | null = null;

const now = (): number => Date.now();
const vibrate = (): void => {
  navigator.vibrate?.(50);
};

let gpsWatchdog: number | undefined;

/** 定位错误 → 用户能看懂的中文提示（之前静默吞掉是"手机无法定位"的元凶之一） */
function handleGpsError(kind: GpsErrorKind): void {
  if (view !== 'record') return;
  const tips: Record<GpsErrorKind, string> = {
    unsupported: '此浏览器不支持定位，请换系统浏览器（如 Safari / Chrome）打开',
    denied:
      '定位权限被拒绝：请点浏览器地址栏旁的锁形图标 → 允许定位，然后重新按「开始拜年」',
    unavailable:
      '无法获取定位：请确认系统定位服务已开启、人在开阔处；室内可能搜不到卫星',
    timeout:
      '定位超时：请到室外开阔处重试；若在微信内打开，请改用系统浏览器（微信内置浏览器常拦截定位）',
  };
  ui.toast(tips[kind]);
}

/** 把当前会话状态同步到地图（轨迹/节点/Home/当前位置） */
function syncMap(): void {
  if (!mapCtrl || !recorder) return;
  const s = recorder.snapshot;
  mapCtrl.setTrack(s.points.map((p) => p.pos));
  mapCtrl.setNodes(s.home, s.nodes);
  const last = s.points[s.points.length - 1];
  if (last) mapCtrl.follow(last.pos.lat, last.pos.lng);
  else mapCtrl.follow(s.home.lat, s.home.lng);
}

function leaveRecord(): void {
  mapCtrl?.destroy();
  mapCtrl = null;
}

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

function mountRecord(): Ui {
  ui = mountUi(app, {
    onStart() {
      if (recorder) return;
      pendingStart = true;
      try {
        gps.start({ onFix, onError: handleGpsError });
        void requestWakeLock();
        ui.toast('正在获取定位…');
        // 30 秒看门狗：还没拿到定位就给提示，绝不无声卡住
        window.clearTimeout(gpsWatchdog);
        gpsWatchdog = window.setTimeout(() => {
          if (pendingStart && !recorder) {
            ui.toast(
              '还没拿到定位：请检查定位权限/是否在室内；如在微信内请改用系统浏览器打开',
            );
          }
        }, 30000);
      } catch (e) {
        pendingStart = false;
        ui.toast(e instanceof Error ? e.message : '定位启动失败');
      }
    },
    onPause() {
      if (!recorder) return;
      try {
        recorder.pause(gps.recent(3), now());
        gps.stop(); // SPEC §7：PAUSED 停 GPS（省电 + 防屋内漂移）
        vibrate();
        flush();
        syncMap();
      } catch (e) {
        ui.toast((e as Error).message);
      }
    },
    onResume() {
      if (!recorder) return;
      recorder.resume(now());
      try {
        gps.start({ onFix, onError: handleGpsError }); // 离开该户重新采样
      } catch (e) {
        ui.toast((e as Error).message);
      }
      vibrate();
      flush();
      syncMap();
    },
    onUndo() {
      if (!recorder) return;
      if (recorder.undo()) {
        flush();
        syncMap();
      } else {
        ui.toast('没有可撤销的操作');
      }
    },
    onFinish() {
      if (!recorder) return;
      const res = recorder.finish(gps.recent(3), now());
      if (!res.ok) {
        const go = ui.confirm(
          `当前位置距 Home 约 ${Math.round(res.distM)} 米，仍要结束吗？`,
        );
        if (!go) return;
        recorder.finish(gps.recent(3), now(), true);
      }
      complete();
    },
    onMode(m) {
      if (!recorder) return;
      recorder.setMode(m, now());
      flush();
      ui.toast(m === 'bike' ? '下一段将骑行前往，到户自动回走路' : '已切回步行');
    },
    onExport() {
      void doExport()
        .then(() => ui.toast('已导出备份 JSON'))
        .catch((e: unknown) => ui.toast(e instanceof Error ? e.message : '导出失败'));
    },
    onHistory() {
      void showHistory();
    },
    onPlan() {
      showPlanView();
    },
  });
  mapCtrl = mountMap(
    app.querySelector<HTMLElement>('#map')!,
    recorder ? recorder.snapshot.home : null,
  );
  syncMap();
  return ui;
}

let ui: Ui;
ui = mountRecord();

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
    window.clearTimeout(gpsWatchdog);
    vibrate();
    flush();
    syncMap();
    ui.toast('开始记录！到一户按「暂停」');
    return;
  }
  if (recorder) {
    try {
      recorder.addPoint(f.pos, f.acc, now());
      if (view === 'record') mapCtrl?.follow(f.pos.lat, f.pos.lng, f.acc);
    } catch {
      /* 非 WALKING 状态，忽略 */
    }
  }
}

function complete(): void {
  if (!recorder) return;
  gps.stop();
  void releaseWakeLock();
  const saved = recorder.snapshot;
  recorder = null;
  void saveSession(saved).catch((e) => console.warn('[db]', e));
  void clearActive();
  vibrate();
  showReview(saved);
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
}

function showReview(sess: SessionData): void {
  leaveRecord();
  view = 'review';
  mountReviewView(app, sess, {
    onBack: () => void showHistory(),
    onSave: (s2) => void saveSession(s2).catch((e) => console.warn('[db]', e)),
    onOptimize: (s2) => {
      view = 'optimize';
      mountOptimizeView(app, s2, {
        onBack: () => showReview(s2),
      });
    },
    loadPlan: (year) => loadPlan(year),
    loadPrev: async (year) => {
      const sessions = (await listSessions()).sort(
        (a, b) => b.createdAt - a.createdAt,
      );
      return sessions.find((x) => x.year < year);
    },
  });
}

function showPlanView(): void {
  leaveRecord();
  view = 'plan';
  mountPlanView(app, new Date().getFullYear(), {
    onBack: () => {
      view = 'record';
      ui = mountRecord();
    },
    loadPlan: (year) => loadPlan(year),
    savePlan: (p) => savePlan(p),
    listSessions: () => listSessions(),
  });
}

async function showHistory(): Promise<void> {
  leaveRecord();
  view = 'history';
  const sessions = (await listSessions()).sort((a, b) => b.createdAt - a.createdAt);
  app.innerHTML = `
    <div class="wrap">
      <header><h1>历史记录</h1></header>
      <button id="h-back" class="secondary">← 返回记录</button>
      <button id="h-demo" class="secondary">生成演示数据</button>
      <div class="h-actions">
        <button id="h-export" class="primary small">导出 JSON</button>
        <button id="h-import" class="ghost small">导入 JSON</button>
        <input id="h-file" type="file" accept="application/json,.json" style="display:none"/>
      </div>
      <p class="h-hint">导出/导入与安卓版同一格式：安卓记录 → 电脑复盘，或反之。</p>
      <div id="h-list" class="history-list"></div>
      <div id="toast"></div>
    </div>`;
  const toastEl = app.querySelector<HTMLElement>('#toast')!;
  const toast = (msg: string): void => {
    toastEl.textContent = msg;
    toastEl.classList.add('show');
    window.setTimeout(() => toastEl.classList.remove('show'), 2400);
  };
  app.querySelector<HTMLElement>('#h-back')!.addEventListener('click', () => {
    view = 'record';
    ui = mountRecord();
  });
  app.querySelector<HTMLElement>('#h-demo')!.addEventListener('click', () => {
    // 演示会话不落库，仅在内存中回放/收拾
    showReview(generateDemoSession());
  });
  app.querySelector<HTMLElement>('#h-export')!.addEventListener('click', () => {
    void doExport().catch((e: unknown) => toast(e instanceof Error ? e.message : '导出失败'));
  });
  const fileInput = app.querySelector<HTMLInputElement>('#h-file')!;
  app.querySelector<HTMLElement>('#h-import')!.addEventListener('click', () => fileInput.click());
  fileInput.addEventListener('change', () => {
    const f = fileInput.files?.[0];
    if (!f) return;
    const reader = new FileReader();
    reader.onload = () => {
      importAllJson(String(reader.result ?? ''))
        .then(async (n) => {
          toast(`已导入 ${n} 场拜年记录`);
          await showHistory();
        })
        .catch((e: unknown) => toast(e instanceof Error ? e.message : '导入失败'));
    };
    reader.onerror = () => toast('读取文件失败');
    reader.readAsText(f);
    fileInput.value = '';
  });
  const list = app.querySelector<HTMLElement>('#h-list')!;
  list.innerHTML =
    sessions.length === 0
      ? '<p class="empty">还没有记录。大年初一，出发！</p>'
      : sessions
          .map((s) => {
            let stat: string;
            try {
              const c = scorecard(s, optimizeSession(s));
              stat = `${s.nodes.length} 户 · ${(c.actualDistM / 1000).toFixed(2)} km · 绕路率 ${c.savingsTimePct.toFixed(0)}%`;
            } catch {
              stat = `${s.nodes.length} 户`;
            }
            return `<button class="history-item" data-id="${s.id}">${s.date} · ${stat}</button>`;
          })
          .join('');
  list.querySelectorAll<HTMLElement>('[data-id]').forEach((b) => {
    b.addEventListener('click', () => {
      const sess = sessions.find((x) => x.id === b.dataset.id);
      if (sess) showReview(sess);
    });
  });
}

async function boot(): Promise<void> {
  try {
    const ck = await loadActive();
    if (ck && !ck.session.finished) {
      const resume = ui.confirm('检测到未完成的拜年记录，继续吗？（取消=放弃）');
      if (resume) {
        recorder = RecorderState.restore(ck);
        if (recorder.state === 'WALKING') {
          gps.start({ onFix, onError: handleGpsError });
        }
        syncMap();
        ui.toast('已恢复未完成的记录');
      } else {
        await clearActive();
      }
    }
  } catch (e) {
    console.warn('[db]', e);
  }
}

setInterval(() => {
  if (view === 'record') {
    ui.render(recorder?.snapshot ?? null, elapsedMs(), {
      acc: gps.lastFix?.acc ?? null,
      waiting: pendingStart,
    });
  }
}, 1000);
setInterval(flush, 10_000); // D22：每 10s 检查点落盘
ui.render(null, 0);
void boot();

// PWA 离线：仅生产环境注册（开发环境不缓存，避免 HMR 污染）
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => {
    navigator.serviceWorker
      .register('./sw.js')
      .catch((e) => console.warn('[sw]', e));
  });
}
