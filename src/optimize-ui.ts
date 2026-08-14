/** 三线对比视图（M3 UI）：成绩单 + 三线推演动画 + 压轴 morph（D7/U1/U3/F-14）
 * D22.1：重计算（TSP/成绩单/回放计划/morph 重采样）经 compute.ts 走 Worker，无 Worker 环境同步回退 */

import type { SessionData } from './state.js';
import { projectToView } from './track.js';
import { lerpPolyline, type XY } from './polyline.js';
import type { Route } from './optimize.js';
import { analyze, analyzeSync, morph, morphSync, type Card } from './compute.js';
import { ICONS } from './icons.js';

const W = 480;
const H = 560;
const MORPH_MS = 3000;

type Tab = 'walk_time' | 'walk_dist' | 'fly';

const TAB_META: Record<Tab, { label: string; color: string; icon: string }> = {
  walk_time: { label: '走路时间最优', color: '#c8402f', icon: ICONS.walk },
  walk_dist: { label: '走路距离最优', color: '#e8a23d', icon: ICONS.ruler },
  fly: { label: '飞行最优', color: '#c9971c', icon: ICONS.plane },
};

const fmtKm = (m: number): string => `${(m / 1000).toFixed(2)} km`;
const fmtMin = (sec: number): string =>
  `${Math.floor(sec / 60)} 分 ${Math.round(sec % 60)} 秒`;
const fmtPct = (p: number): string => `${p.toFixed(0)}%`;
// P11：标题等插入 innerHTML 的外部数据一律转义
const esc = (s: string): string =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

export interface OptimizeDeps {
  onBack(): void;
}

export function mountOptimizeView(
  root: HTMLElement,
  s: SessionData,
  deps: OptimizeDeps,
): void {
  let ready = false;
  let routes: Route[] = [];
  let card: Card | null = null;
  let planPts: XY[] = [];
  let actualRes: XY[] = [];
  let flyRes: XY[] = [];

  let tab: Tab = 'walk_time';
  let revealK = 0; // 已点亮边数
  let morphT: number | null = null; // null=隐藏
  let raf = 0;

  root.innerHTML = `
    <div class="wrap">
      <div class="bar">
        <button id="opt-back" class="secondary small">← 复盘</button>
        <div class="bar-title">${esc(s.date)} · 三线对比</div>
      </div>
      <div class="cards" id="opt-cards"></div>
      <div class="canvas-box">
        <svg id="opt-svg" viewBox="0 0 ${W} ${H}"></svg>
      </div>
      <div class="pctrl">
        <button id="opt-tab-time" class="secondary small">${TAB_META.walk_time.icon}${TAB_META.walk_time.label}</button>
        <button id="opt-tab-dist" class="secondary small">${TAB_META.walk_dist.icon}${TAB_META.walk_dist.label}</button>
        <button id="opt-tab-fly" class="secondary small">${TAB_META.fly.icon}${TAB_META.fly.label}</button>
      </div>
      <div class="pctrl">
        <button id="opt-reveal" class="small">${ICONS.play}推演</button>
        <button id="opt-morph" class="small">${ICONS.star}压轴动画</button>
        <button id="opt-reset" class="secondary small">重置</button>
      </div>
      <p class="hint" id="opt-hint"></p>
    </div>
  `;

  const $ = (id: string): HTMLElement => root.querySelector<HTMLElement>('#' + id)!;
  const svg = $('opt-svg');

  const route = (): Route => routes.find((r) => r.mode === tab)!;
  const posOf = (id: string) =>
    id === 'home' ? s.home : s.nodes.find((n) => n.id === id)!.pos;
  const proj = (id: string) => projectToView([posOf(id)], W, H)[0];

  function renderCards(): void {
    if (!card) {
      $('opt-cards').innerHTML = '<p class="empty">计算中…</p>';
      return;
    }
    $('opt-cards').innerHTML = `
      <div class="card">
        <span>🧧 今年实走</span>
        <b>${fmtKm(card.actualDistM)}</b>
        <span>路上 ${fmtMin(card.actualMoveSec)} · 骑行 ${fmtKm(card.bikeDistM)} · 全天 ${fmtMin(card.actualTotalSec)}</span>
      </div>
      <div class="card">
        <span>🚶 走路时间最优（理论）</span>
        <b>${fmtMin(card.timeOptSec)}</b>
        <span>比实走路上时间省 ${fmtPct(card.savingsTimePct)}</span>
      </div>
      <div class="card">
        <span>📏 距离最优</span>
        <b>${fmtKm(card.distOptM)}</b>
        <span>省 ${fmtPct(card.savingsDistPct)}</span>
      </div>
      <div class="card">
        <span>✈️ 如果能飞</span>
        <b>${fmtKm(card.flyOptM)}</b>
        <span>少走 ${fmtPct(card.savingsFlyPct)}</span>
      </div>`;
  }

  function renderSvg(): void {
    if (!ready) return;
    const r = route();
    const meta = TAB_META[tab];
    const actualOpacity =
      morphT !== null ? Math.max(0, 1 - morphT) * 0.45 : 0.3;
    const actualD = planPts
      .map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)},${p.y.toFixed(1)}`)
      .join(' ');

    const edgeSvg = r.edges
      .map((e, k) => {
        const a = proj(e.from);
        const b = proj(e.to);
        const lit = k < revealK;
        const dash = e.known ? '' : 'stroke-dasharray="6 5"';
        return `<line x1="${a.x.toFixed(1)}" y1="${a.y.toFixed(1)}" x2="${b.x.toFixed(1)}" y2="${b.y.toFixed(1)}"
          stroke="${meta.color}" stroke-width="${lit ? 4 : 1.6}" opacity="${lit ? 1 : 0.35}" ${dash}/>`;
      })
      .join('');

    const nodeSvg = r.order
      .map((id, k) => {
        const p = proj(id);
        const isHome = id === 'home';
        const label = isHome ? '家' : String(k);
        const reached = k <= revealK;
        // 家字画在金色圆内（白字），不再悬浮于圆上方——任何布局下都不会与其它元素重叠
        const textY = isHome ? p.y + 5.5 : p.y - 14;
        return (
          `<g class="node${isHome ? ' home' : ''}${reached ? ' lit' : ''}">` +
          `<circle cx="${p.x.toFixed(1)}" cy="${p.y.toFixed(1)}" r="${isHome ? 10 : 9}"/>` +
          `<text x="${p.x.toFixed(1)}" y="${textY.toFixed(1)}">${label}</text>` +
          `</g>`
        );
      })
      .join('');

    let morphSvg = '';
    if (morphT !== null && actualRes.length > 0 && flyRes.length > 0) {
      const mp = lerpPolyline(actualRes, flyRes, morphT);
      morphSvg = `<path d="${mp
        .map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)},${p.y.toFixed(1)}`)
        .join(' ')}" class="opt-morph"/>`;
    }

    svg.innerHTML =
      `<path d="${actualD}" class="opt-actual" opacity="${actualOpacity.toFixed(2)}"/>` +
      edgeSvg +
      nodeSvg +
      morphSvg;
  }

  function renderHint(): void {
    if (!ready) return;
    const r = route();
    const unknown = r.edges.filter((e) => !e.known).length;
    $('opt-hint').textContent =
      tab === 'fly'
        ? '飞行视角：直线距离，纯几何幻想'
        : `虚线 = 今年没走过的路段（估算）${unknown > 0 ? ` · ${unknown} 段` : ''}；实线 = 实走数据`;
  }

  function renderAll(): void {
    renderCards();
    renderSvg();
    renderHint();
    updateTabButtons();
  }

  function updateTabButtons(): void {
    for (const t of ['walk_time', 'walk_dist', 'fly'] as Tab[]) {
      $('opt-tab-' + (t === 'walk_time' ? 'time' : t === 'walk_dist' ? 'dist' : 'fly')).classList.toggle(
        'active',
        t === tab,
      );
    }
  }

  function stopAnim(): void {
    cancelAnimationFrame(raf);
  }

  function playReveal(): void {
    if (!ready) return;
    stopAnim();
    morphT = null;
    revealK = 0;
    const r = route();
    const totalMs = Math.max(1000, r.edges.length * 350);
    const t0 = performance.now();
    const loop = (): void => {
      const f = Math.min(1, (performance.now() - t0) / totalMs);
      revealK = Math.floor(f * r.edges.length);
      renderSvg();
      if (f < 1) raf = requestAnimationFrame(loop);
      else {
        revealK = r.edges.length;
        renderSvg();
      }
    };
    raf = requestAnimationFrame(loop);
  }

  function playMorph(): void {
    if (!ready || planPts.length < 2 || flyRes.length < 2) return;
    stopAnim();
    revealK = route().edges.length;
    morphT = 0;
    const t0 = performance.now();
    // §9 红线：easeInOutCubic（P8 修复）
    const ease = (t: number): number =>
      t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    const loop = (): void => {
      const f = Math.min(1, (performance.now() - t0) / MORPH_MS);
      morphT = ease(f);
      renderSvg();
      if (f < 1) raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
  }

  $('opt-back').addEventListener('click', () => {
    stopAnim();
    deps.onBack();
  });
  $('opt-tab-time').addEventListener('click', () => switchTab('walk_time'));
  $('opt-tab-dist').addEventListener('click', () => switchTab('walk_dist'));
  $('opt-tab-fly').addEventListener('click', () => switchTab('fly'));
  $('opt-reveal').addEventListener('click', playReveal);
  $('opt-morph').addEventListener('click', playMorph);
  $('opt-reset').addEventListener('click', () => {
    stopAnim();
    morphT = null;
    revealK = route().edges.length;
    renderSvg();
  });

  function switchTab(t: Tab): void {
    if (!ready) return;
    stopAnim();
    tab = t;
    morphT = null;
    revealK = route().edges.length; // 切换即显示完整路线
    renderAll();
  }

  function applyResult(ar: { routes: Route[]; card: Card; planPts: XY[] }, mr: { actualRes: XY[]; flyRes: XY[] }): void {
    routes = ar.routes;
    card = ar.card;
    planPts = ar.planPts;
    actualRes = mr.actualRes;
    flyRes = mr.flyRes;
    ready = true;
    revealK = route().edges.length;
    renderAll();
  }

  if (typeof Worker === 'undefined') {
    // 无 Worker 环境（jsdom 测试等）：同步回退，保证挂载后立即可交互
    const ar = analyzeSync(s);
    applyResult(ar, morphSync(s, ar.routes));
  } else {
    void analyze(s).then((ar) => {
      void morph(s, ar.routes).then((mr) => applyResult(ar, mr));
    });
  }
  renderCards();
}
