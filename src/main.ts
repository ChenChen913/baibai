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
import { analyze, analyzeSync, type AnalyzeResult } from './compute.js';
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
import { cnyLabel, fromDateStr, toDateStr } from './cny.js';
import { preloadTileList } from './tiles.js';

const app = document.querySelector<HTMLElement>('#app')!;
const gps = new GpsTracker();

// 拜年日期（默认今天；用户可改——初一/初二/初三……哪一天拜年都行）
const BIZ_DATE_KEY = 'baibai_biz_date';
function loadBizDate(): Date {
  try {
    const s = localStorage.getItem(BIZ_DATE_KEY);
    if (s) {
      const d = fromDateStr(s);
      if (!Number.isNaN(d.getTime())) return d;
    }
  } catch {
    /* 存储不可用则忽略 */
  }
  return new Date();
}
let bizDate = loadBizDate();

/** P11：所有插入 innerHTML 的用户/外部数据一律转义 */
const escHtml = (s: string): string =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
let recorder: RecorderState | null = null;
let pendingStart = false;
// 首个定位的缓冲：攒 3 个 fix（或 3 秒）取中位数定 Home——单个 fix 噪声大（±10m+）
const pendingFixes: Fix[] = [];
let pendingStartAt = 0;
// R9（定位只做一次）：最近一次 fix 到达时刻 + 缓存的 fix 本体——
// 再次点「开始」时若缓存够新（10 分钟内）直接复用定 Home，不再重新等待定位
let lastFixArrivedAt = 0;
const FIX_REUSE_MS = 10 * 60 * 1000;
let wakeLock: { release: () => Promise<void> } | null = null;
let view: 'record' | 'history' | 'review' | 'optimize' | 'plan' = 'record';
let mapCtrl: MapController | null = null;

const now = (): number => Date.now();
// D19 R3：震动 + 轻音效（默认开启，可关——P6）
const FEEDBACK_KEY = 'baibai_feedback';
const feedbackOn = (): boolean => {
  try {
    return localStorage.getItem(FEEDBACK_KEY) !== 'off';
  } catch {
    return true;
  }
};
const vibrate = (): void => {
  if (feedbackOn()) navigator.vibrate?.(50);
};
function beep(): void {
  if (!feedbackOn()) return;
  try {
    const ctx = new AudioContext();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.value = 880;
    gain.gain.value = 0.06;
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.05);
    window.setTimeout(() => {
      void ctx.close();
    }, 200);
  } catch {
    /* 不支持音频则静默 */
  }
}

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

// P0：瓦片预载（每个 Home 只自动预载一次；手动按钮随时可再触发）
let preloadedFor = '';
function startTilePreload(home: { lat: number; lng: number }): void {
  const tiles = preloadTileList(home.lat, home.lng);
  const ctrl = navigator.serviceWorker?.controller;
  if (!ctrl) {
    ui.toast('离线预载需要 Service Worker（线上版本可用）');
    return;
  }
  ctrl.postMessage({ type: 'baibai-preload', tiles });
  ui.toast('正在预载周边地图（' + tiles.length + ' 张）…');
}
function maybePreloadTiles(home: { lat: number; lng: number }): void {
  const key = home.lat.toFixed(4) + ',' + home.lng.toFixed(4);
  if (key === preloadedFor) return;
  preloadedFor = key;
  startTilePreload(home);
}

/** 把当前会话状态同步到地图（轨迹/节点/Home/当前位置） */
function syncMap(): void {
  if (!mapCtrl || !recorder) return;
  const s = recorder.snapshot;
  // 段起点下标：走过段淡红、当前段实红粗线（像导航一样实时增长）
  const breaks: number[] = [];
  let curSeg = '';
  s.points.forEach((p, i) => {
    if (p.seg !== curSeg) {
      breaks.push(i);
      curSeg = p.seg;
    }
  });
  mapCtrl.setTrack(s.points.map((p) => p.pos), breaks);
  mapCtrl.setNodes(s.home, s.nodes);
  if (s.home.lat !== 0 || s.home.lng !== 0) maybePreloadTiles(s.home);
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
  // R9：等定位期间也计时（从点击「开始」那刻起）——用户主诉"点开始后用时一直是 0"，
  // 根因是旧版 waiting 阶段返回 0、且会话 createdAt 从拿到定位才起算，室内等 30 秒全程 00:00:00
  if (!recorder) {
    return pendingStart ? Math.max(0, now() - pendingStartAt) : 0;
  }
  // t0 用会话创建时刻（= 点击「开始」时刻，onFix 创建时显式传入），不用 points[0].t——
  // 首点迟到会让计时倒退（实机复现：定位稀疏时时间回跳/停滞）
  return Math.max(0, now() - recorder.snapshot.createdAt);
}

async function refreshHistoryDrawer(): Promise<void> {
  const sessions = (await listSessions()).sort((a, b) => b.createdAt - a.createdAt);
  const statsMap = new Map<string, string>();
  for (const s of sessions) {
    statsMap.set(s.id, await statFor(s));
  }
  ui.setHistorySessions(sessions, statsMap);
}

function mountRecord(): Ui {
  ui = mountUi(app, {
    onStart() {
      if (recorder) return;
      pendingStartAt = now();
      // R9（定位只做一次）：10 分钟内已有定位 → 直接复用定 Home，秒开记录，不再重新等待。
      // 用户主诉：结束一次后再点「开始」又要重新等定位——拜年场景两次开始间隔通常只有几分钟
      const cached = gps.lastFix;
      if (cached && lastFixArrivedAt > 0 && now() - lastFixArrivedAt <= FIX_REUSE_MS) {
        const r = new RecorderState({
          date: toDateStr(bizDate),
          year: bizDate.getFullYear(),
          createdAt: pendingStartAt, // R9：用时从点击「开始」起算
        });
        try {
          r.start([cached], now(), cached);
        } catch (e) {
          ui.toast((e as Error).message);
          return;
        }
        recorder = r;
        pendingStart = false;
        window.clearTimeout(gpsWatchdog);
        vibrate();
        beep();
        flush();
        syncMap();
        // 复用只省「定 Home 的等待」；WALKING 的轨迹记录仍需立即恢复持续定位
        gps.start({ onFix, onError: handleGpsError });
        void requestWakeLock();
        ui.toast('定位已复用，开始记录！到一户按「暂停」');
        return;
      }
      pendingStart = true;
      pendingFixes.length = 0;
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
        recorder.pause(gps.recent(10), now());
        gps.stop(); // SPEC §7：PAUSED 停 GPS（省电 + 防屋内漂移）
        vibrate();
        beep();
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
      beep();
      flush();
      syncMap();
    },
    onFinish() {
      if (!recorder) return;
      const res = recorder.finish(gps.recent(10), now());
      if (!res.ok) {
        // P18：无定位时不显示 Infinity；P17：§7.8 文案与按钮
        const distText = Number.isFinite(res.distM)
          ? `当前位置距 Home 约 ${Math.round(res.distM)} 米`
          : '当前位置无法定位';
        void confirmDialog('结束拜年', `${distText}，仍要结束吗？`, '强制结束', '取消').then((go) => {
          if (!go || !recorder) return;
          try {
            recorder.finish(gps.recent(10), now(), true);
            complete();
          } catch (e) {
            ui.toast((e as Error).message);
          }
        });
        return;
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
      void refreshHistoryDrawer();
    },
    onPlan() {
      void loadPlan(bizDate.getFullYear()).then((p) => {
        ui.setPlan(p ?? null);
      });
    },
    onFeedbackToggle() {
      toggleFeedback();
    },
    onDatePick() {
      pickBizDate();
    },
    onPreload() {
      const home = recorder?.snapshot.home;
      if (home && (home.lat !== 0 || home.lng !== 0)) {
        startTilePreload(home);
      } else {
        ui.toast('先开始拜年拿到定位，再点「预载」');
      }
    },
    onRecenter() {
      mapCtrl?.recenter();
    },
    onFitBounds() {
      mapCtrl?.fitBounds();
    },
    onLayerSwitch() {
      const next = mapCtrl?.switchTileLayer();
      ui.toast(next === 'sat' ? '已切换至高德卫星影像' : '已切换至高德矢量地图');
    },
    onFocusNode(no) {
      mapCtrl?.focusNode(no);
    },
    onAddHouse() {
      showPlanView();
    },
    onImportPlan() {
      showPlanView();
    },
    onSelectHistory(id) {
      void listSessions().then((list) => {
        const found = list.find((s) => s.id === id);
        if (found) showReview(found, 'record'); // 抽屉点开 → 返回记录页
      });
    },
    onDemoHistory() {
      showReview(generateDemoSession(), 'record');
    },
    onImportJson(file) {
      const reader = new FileReader();
      reader.onload = () => {
        importAllJson(String(reader.result ?? ''))
          .then(async (n) => {
            ui.toast(`已导入 ${n} 场拜年记录`);
            await refreshHistoryDrawer();
          })
          .catch((e: unknown) => ui.toast(e instanceof Error ? e.message : '导入失败'));
      };
      reader.onerror = () => ui.toast('读取文件失败');
      reader.readAsText(file);
    },
    onShowAllHistory() {
      void showHistory(); // 抽屉「查看全部历史」→ 完整历史页（导出/导入/成绩单全量版）
    },
  });
  mapCtrl = mountMap(
    app.querySelector<HTMLElement>('#map')!,
    recorder ? recorder.snapshot.home : null,
  );
  syncMap();
  ui.setFeedbackOn(feedbackOn());
  ui.setDateLabel(cnyLabel(bizDate));
  void loadPlan(bizDate.getFullYear()).then((p) => {
    ui.setPlan(p ?? null);
  });
  return ui;
}

/** 拜年日期选择对话框（原生 date 输入 → 手机自带日期选择器） */
function pickBizDate(): void {
  const overlay = document.createElement('div');
  overlay.className = 'dlg-overlay';
  const el = document.createElement('div');
  el.className = 'dlg';
  const h = document.createElement('h3');
  h.textContent = '选择拜年日期';
  const p = document.createElement('p');
  p.textContent = '初一、初二、初三……选哪天拜年都行，本次记录与回放都会用这个日期。';
  const input = document.createElement('input');
  input.type = 'date';
  input.value = toDateStr(bizDate);
  input.className = 'date-input';
  const actions = document.createElement('div');
  actions.className = 'dlg-actions';
  const ok = document.createElement('button');
  ok.className = 'primary small';
  ok.textContent = '确定';
  const cancel = document.createElement('button');
  cancel.className = 'ghost small';
  cancel.textContent = '取消';
  actions.append(ok, cancel);
  el.append(h, p, input, actions);
  overlay.appendChild(el);
  const done = (): void => overlay.remove();
  ok.addEventListener('click', () => {
    if (!input.value) return;
    bizDate = fromDateStr(input.value);
    try {
      localStorage.setItem(BIZ_DATE_KEY, toDateStr(bizDate));
    } catch {
      /* 存储不可用则忽略 */
    }
    ui.setDateLabel(cnyLabel(bizDate));
    ui.toast('拜年日期已设为 ' + cnyLabel(bizDate));
    done();
  });
  cancel.addEventListener('click', done);
  app.appendChild(overlay);
}

function toggleFeedback(): void {
  const next = !feedbackOn();
  try {
    localStorage.setItem(FEEDBACK_KEY, next ? 'on' : 'off');
  } catch {
    /* 隐私模式等存储不可用则忽略 */
  }
  ui.setFeedbackOn(next);
  ui.toast(next ? '提示音与震动已开启' : '提示音与震动已关闭');
}

let ui: Ui;
ui = mountRecord();

function onFix(f: Fix): void {
  lastFixArrivedAt = now(); // R9：记录 fix 到达时刻（供下次「开始」判断缓存时效）
  if (pendingStart && !recorder) {
    // 攒 3 个 fix（或 3 秒）→ 中位数定 Home；单个 fix 噪声太大
    pendingFixes.push(f);
    if (pendingFixes.length >= 3 || now() - pendingStartAt >= 3000) {
      recorder = new RecorderState({
        date: toDateStr(bizDate),
        year: bizDate.getFullYear(),
        createdAt: pendingStartAt, // R9：用时从点击「开始」起算（含等定位阶段）
      });
      try {
        recorder.start(pendingFixes, now(), f);
      } catch (e) {
        recorder = null;
        pendingFixes.length = 0;
        ui.toast((e as Error).message);
        return;
      }
      pendingFixes.length = 0;
      pendingStart = false;
      window.clearTimeout(gpsWatchdog);
      vibrate();
      beep();
      flush();
      syncMap();
      ui.toast('开始记录！到一户按「暂停」');
    }
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
  beep();
  showReview(saved, 'record'); // 刚结束复盘 → 返回键回记录页，可立即开始下一场
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

/**
 * 回顾页：from 记录进入来源，决定「返回」落点——
 * 'record'：刚结束复盘/记录页历史抽屉点开 → 返回记录页（可立即开始下一场）
 * 'history'：从历史页列表点开 → 返回历史页（继续翻别的场次）
 */
function showReview(sess: SessionData, from: 'record' | 'history'): void {
  leaveRecord();
  view = 'review';
  const backTo = (): void => {
    if (from === 'history') void showHistory();
    else {
      view = 'record';
      ui = mountRecord();
    }
  };
  mountReviewView(app, sess, {
    onBack: backTo,
    onSave: (s2) => void saveSession(s2).catch((e) => console.warn('[db]', e)),
    onOptimize: (s2) => {
      view = 'optimize';
      mountOptimizeView(app, s2, {
        onBack: () => showReview(s2, from),
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
      <div id="h-summary" class="h-summary"></div>
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
    showReview(generateDemoSession(), 'history'); // 历史页点开 → 返回历史页
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
  const summary = app.querySelector<HTMLElement>('#h-summary')!;
  if (sessions.length === 0) {
    summary.innerHTML = '';
    list.innerHTML = '<p class="empty">还没有记录。大年初一，出发！</p>';
  } else {
    list.innerHTML = sessions
      .map((s) => `<button class="history-item" data-id="${s.id}">${escHtml(s.date)} · 计算中…</button>`)
      .join('');
    void Promise.all(
      sessions.map(async (s) => ({ s, stat: await statFor(s) })),
    ).then((rows) => {
      rows.forEach(({ s, stat }) => {
        const el = list.querySelector<HTMLElement>(`[data-id="${s.id}"]`);
        if (el) el.textContent = `${s.date} · ${stat}`;
      });
      renderYearSummary(summary, rows); // P15：历年成绩单 + 绕路率趋势线
    });
  }
  list.querySelectorAll<HTMLElement>('[data-id]').forEach((b) => {
    b.addEventListener('click', () => {
      const sess = sessions.find((x) => x.id === b.dataset.id);
      if (sess) showReview(sess, 'history'); // 历史页点开 → 返回历史页
    });
  });
}

/** P15/F-15：历年成绩单卡片 + 绕路率逐年趋势线（按年聚合，不混数据） */
function renderYearSummary(
  el: HTMLElement,
  rows: { s: SessionData; stat: string }[],
): void {
  const byYear = new Map<number, { n: number; km: number; sumPct: number }>();
  for (const { s, stat } of rows) {
    const m = /(\d+\.\d+) km .*绕路率 (\d+)%/.exec(stat);
    const km = m ? parseFloat(m[1]) : 0;
    const pct = m ? parseInt(m[2], 10) : 0;
    const cur = byYear.get(s.year) ?? { n: 0, km: 0, sumPct: 0 };
    cur.n += 1;
    cur.km += km;
    cur.sumPct += pct;
    byYear.set(s.year, cur);
  }
  const years = [...byYear.keys()].sort((a, b) => a - b);
  const cards = years
    .map((y) => {
      const v = byYear.get(y)!;
      return `<div class="year-card"><b>${y} 年</b><span>${v.n} 场 · ${v.km.toFixed(2)} km · 平均绕路率 ${Math.round(v.sumPct / v.n)}%</span></div>`;
    })
    .join('');
  let chart = '';
  if (years.length >= 2) {
    const cw = 300;
    const ch = 60;
    const pad = 8;
    const pts = years.map((y) => {
      const v = byYear.get(y)!;
      const avg = v.sumPct / v.n;
      const x = pad + ((y - years[0]) / Math.max(1, years[years.length - 1] - years[0])) * (cw - pad * 2);
      const yc = ch - pad - (Math.min(100, avg) / 100) * (ch - pad * 2);
      return { x, y: yc, avg };
    });
    const line = pts.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ');
    const dots = pts
      .map(
        (p) =>
          `<circle cx="${p.x.toFixed(1)}" cy="${p.y.toFixed(1)}" r="3" fill="#c8402f"/>` +
          `<text x="${p.x.toFixed(1)}" y="${(p.y - 6).toFixed(1)}" text-anchor="middle" font-size="8" fill="#5a3a2a">${Math.round(p.avg)}%</text>`,
      )
      .join('');
    chart = `<div class="trend"><span class="trend-title">绕路率趋势（逐年平均）</span>` +
      `<svg viewBox="0 0 ${cw} ${ch}">${dots}<path d="${line}" fill="none" stroke="#c8402f" stroke-width="2" stroke-linecap="round"/></svg></div>`;
  }
  el.innerHTML = cards + chart;
}

/** 会话统计（D22.1 Worker 化 + P26 内存缓存：同一会话多次进历史不重算） */
const analyzeCache = new Map<string, AnalyzeResult>();

function statFor(s: SessionData): Promise<string> {
  const statOf = (ar: AnalyzeResult): string =>
    `${s.nodes.length} 户 · ${(ar.card.actualDistM / 1000).toFixed(2)} km · 绕路率 ${ar.card.savingsTimePct.toFixed(0)}%`;
  const key = `${s.id}:${s.updatedAt}`;
  const hit = analyzeCache.get(key);
  if (hit) return Promise.resolve(statOf(hit));
  const fill = (ar: AnalyzeResult): string => {
    analyzeCache.set(key, ar);
    return statOf(ar);
  };
  if (typeof Worker === 'undefined') {
    return Promise.resolve(fill(analyzeSync(s)));
  }
  return analyze(s).then(fill).catch(() => `${s.nodes.length} 户`);
}

async function boot(): Promise<void> {
  try {
    const ck = await loadActive();
    if (ck && !ck.session.finished) {
      // P17：§7.8 规范对话框（标题/正文/继续/放弃）
      const resume = await confirmDialog('检测到未完成的拜年记录', '继续记录，还是放弃？', '继续', '放弃');
      if (resume) {
        recorder = RecorderState.restore(ck);
        if (recorder.state === 'WALKING') {
          gps.start({ onFix, onError: handleGpsError });
          void requestWakeLock(); // P7：恢复续录同样保亮屏
        }
        syncMap();
        ui.toast('已恢复未完成的记录');
      } else {
        await clearActive();
      }
    }
  } catch (e) {
    console.warn('[db]', e);
    // P25：检查点损坏 → 明确提示而非静默
    try {
      ui.toast('上次未完成的记录读取失败，已忽略（可重新开始记录）');
    } catch {
      /* 无 toast 元素则忽略 */
    }
  }
}

/** P17：应用内确认对话框（替代 window.confirm，§7.8 文案规范） */
function confirmDialog(title: string, body: string, okLabel: string, cancelLabel: string): Promise<boolean> {
  return new Promise((resolve) => {
    const overlay = document.createElement('div');
    overlay.className = 'dlg-overlay';
    const el = document.createElement('div');
    el.className = 'dlg';
    const h = document.createElement('h3');
    h.textContent = title;
    const p = document.createElement('p');
    p.textContent = body;
    const actions = document.createElement('div');
    actions.className = 'dlg-actions';
    const ok = document.createElement('button');
    ok.className = 'primary small';
    ok.textContent = okLabel;
    const cancel = document.createElement('button');
    cancel.className = 'ghost small';
    cancel.textContent = cancelLabel;
    actions.append(ok, cancel);
    el.append(h, p, actions);
    overlay.appendChild(el);
    const done = (v: boolean): void => {
      overlay.remove();
      resolve(v);
    };
    ok.addEventListener('click', () => done(true));
    cancel.addEventListener('click', () => done(false));
    app.appendChild(overlay);
  });
}

// 契约常量（数据格式 §9）：检查点落盘间隔 10s（P22 去魔法数字）
const CHECKPOINT_MS = 10_000;

setInterval(() => {
  if (view === 'record') {
    ui.render(recorder?.snapshot ?? null, elapsedMs(), {
      acc: gps.lastFix?.acc ?? null,
      waiting: pendingStart,
    });
  }
}, 1000);
setInterval(flush, CHECKPOINT_MS); // D22：每 10s 检查点落盘
ui.render(null, 0);
void boot();

// P7：页面切后台/锁屏会释放 Wake Lock，回到前台且记录中时重新请求
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && recorder) {
    void requestWakeLock();
  }
});

// PWA 离线：仅生产环境注册（开发环境不缓存，避免 HMR 污染）
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => {
    navigator.serviceWorker
      .register('./sw.js')
      .catch((e) => console.warn('[sw]', e));
  });
}

// 地图折叠/展开后重算 Leaflet 尺寸（P5）
window.addEventListener('baibai-map-resize', () => {
  mapCtrl?.invalidateSize?.();
});

// P0：SW 预载完成回调 → 提示结果
navigator.serviceWorker?.addEventListener('message', (e) => {
  const d = e.data as { type?: string; ok?: number; total?: number } | null;
  if (d?.type === 'baibai-preload-done') {
    try {
      ui.toast(
        (d.ok ?? 0) > 0
          ? '已预载 ' + d.ok + ' 张瓦片，断网也能看地图'
          : '预载失败：请检查网络后重试',
      );
    } catch {
      /* 视图已切换则忽略 */
    }
  }
});

// D22.5 全局错误边界：未捕获异常写日志 + 提示，绝不静默冻结（P3）
function reportUncaught(kind: string, detail: string): void {
  console.error(`[baibai][${kind}]`, detail);
  try {
    localStorage.setItem('baibai_last_error', `${new Date().toISOString()} ${kind}: ${detail}`);
  } catch {
    /* 存储不可用则忽略 */
  }
  try {
    ui.toast('出现异常：请稍后重试，必要时刷新页面（进行中的记录不会丢失）');
  } catch {
    /* 视图上无 toast 元素时忽略 */
  }
}

window.addEventListener('error', (e) => {
  reportUncaught('error', e.error instanceof Error ? e.error.stack ?? e.message : e.message);
});

window.addEventListener('unhandledrejection', (e) => {
  reportUncaught('unhandledrejection', e.reason instanceof Error ? e.reason.stack ?? e.reason.message : String(e.reason));
});
