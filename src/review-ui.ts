/** 回顾页：轨迹回放 + 收拾（改名/合并/拆分/剔除异常点）。UI 层，验收走浏览器/真机。 */

import type { SessionData } from './state.js';
import { buildEdges, projectToView } from './track.js';
import { buildPlan, fractionAt, positionAt, type PlaybackPlan } from './playback.js';
import { mergeNodes, removePoint, renameNode, splitVisit } from './review.js';
import { matchPlan, nameCandidates, type Plan } from './plan.js';
import { ICONS } from './icons.js';

export interface ReviewDeps {
  onBack(): void;
  onSave(s: SessionData): void;
  onOptimize(s: SessionData): void;
  loadPlan(year: number): Promise<Plan | undefined>;
  loadPrev(year: number): Promise<SessionData | undefined>;
}

const W = 480;
const H = 560;

function fmtClock(t: number): string {
  return new Date(t).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
  });
}

function fmtDur(ms: number): string {
  const s = Math.floor(ms / 1000);
  return `${Math.floor(s / 60)}分${s % 60}秒`;
}

function esc(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

export function mountReviewView(
  root: HTMLElement,
  initial: SessionData,
  deps: ReviewDeps,
): void {
  let s = initial;
  let pl: PlaybackPlan = { pts: [], totalMs: 0 };
  let playing = false;
  let speed = 2;
  let baseMs = 0;
  let animStart = 0;
  let raf = 0;
  let plan: Plan | undefined;
  let prevSession: SessionData | undefined;

  root.innerHTML = `
    <div class="wrap">
      <div class="bar">
        <button id="rv-back" class="secondary small">← 返回</button>
        <div class="bar-title" id="rv-title"></div>
        <button id="rv-opt" class="small" style="margin-left:auto">${ICONS.star}三线对比</button>
      </div>
      <div class="stat-line" id="rv-stats"></div>
      <div class="canvas-box">
        <svg id="rv-svg" viewBox="0 0 ${W} ${H}"></svg>
      </div>
      <div class="pctrl">
        <button id="rv-play" class="small">▶ 播放</button>
        <button id="rv-reset" class="secondary small">重置</button>
        <button id="rv-s1" class="secondary small">1x</button>
        <button id="rv-s2" class="secondary small">2x</button>
        <button id="rv-s4" class="secondary small">4x</button>
        <span id="rv-progress" class="pctrl-time"></span>
      </div>
      <div class="section">
        <h2>户名与收拾</h2>
        <div id="rv-nodes"></div>
        <h2>合并两户（误拆并为一户时）</h2>
        <div id="rv-merge">
          <p class="hint" id="rv-merge-hint">点选两户：第一个保留，第二个并入</p>
          <div class="merge-chips" id="rv-merge-chips"></div>
          <button id="rv-merge-btn" class="small" disabled>合并所选两户</button>
        </div>
        <h2>异常跳变点</h2>
        <div id="rv-jumps"></div>
        <h2>漏访检查（今年清单）</h2>
        <div id="rv-plan"></div>
        <h2>套用去年的户名</h2>
        <div id="rv-names"></div>
      </div>
    </div>
  `;

  const $ = (id: string): HTMLElement => root.querySelector<HTMLElement>('#' + id)!;

  function stopAnim(): void {
    playing = false;
    cancelAnimationFrame(raf);
    $('rv-play').textContent = '▶ 播放';
  }

  function mutate(next: SessionData): void {
    s = next;
    deps.onSave(s);
    refresh();
  }

  function refresh(): void {
    stopAnim();
    baseMs = 0;
    pl = buildPlan(s, W, H);
    renderTitle();
    renderStats();
    renderSvg();
    renderNodes();
    renderMerge();
    renderJumps();
    renderPlanSection();
    renderNameSection();
    renderProgress();
  }

  function renderTitle(): void {
    $('rv-title').textContent = `${s.date} · 拜年复盘`;
  }

  function renderStats(): void {
    const edges = buildEdges(s);
    const dist = edges.reduce((sum, e) => sum + e.distM, 0);
    $('rv-stats').textContent =
      `共 ${s.nodes.length} 户 · ${s.visits.length} 次到访 · ` +
      `路程 ${(dist / 1000).toFixed(2)} km · 用时 ${fmtDur(pl.totalMs)}`;
  }

  function renderSvg(): void {
    const d = pl.pts
      .map((q, i) => `${i === 0 ? 'M' : 'L'}${q.x.toFixed(1)},${q.y.toFixed(1)}`)
      .join(' ');
    const nodeSvg = s.nodes
      .map((n) => {
        const pr = projectToView([n.pos], W, H)[0];
        return (
          `<g class="node"><circle cx="${pr.x.toFixed(1)}" cy="${pr.y.toFixed(1)}" r="9"/>` +
          `<text x="${pr.x.toFixed(1)}" y="${(pr.y - 14).toFixed(1)}">${esc(n.name || `户${n.autoNo}`)}</text></g>`
        );
      })
      .join('');
    const homeSvg = (() => {
      const pr = projectToView([s.home], W, H)[0];
      return (
        `<g class="node home"><circle cx="${pr.x.toFixed(1)}" cy="${pr.y.toFixed(1)}" r="10"/>` +
        `<text x="${pr.x.toFixed(1)}" y="${(pr.y - 16).toFixed(1)}">家</text></g>`
      );
    })();
    $('rv-svg').innerHTML =
      `<path d="${d}" class="track-base" pathLength="1"/>` +
      `<path d="${d}" class="track-play" pathLength="1" id="rv-trackplay"/>` +
      `<circle id="rv-dot" r="7" class="dot" style="display:none"/>` +
      nodeSvg +
      homeSvg;
  }

  function renderNodes(): void {
    const box = $('rv-nodes');
    box.innerHTML = s.nodes
      .map((n) => {
        const visits = s.visits.filter((v) => v.nodeId === n.id);
        return (
          `<div class="node-row">` +
          `<span class="nlabel">户${n.autoNo}</span>` +
          `<input id="name-${n.id}" value="${esc(n.name)}" placeholder="起个名（如大伯家）"/>` +
          `<button data-rename="${n.id}" class="secondary small">改名</button>` +
          `<span class="visits">到访 ${visits.length} 次</span>` +
          visits
            .map((v) => {
              const idx = s.visits.indexOf(v);
              return `<button data-split="${idx}" class="secondary small" title="${fmtClock(v.arriveT)} 到">拆第 ${visits.indexOf(v) + 1} 次</button>`;
            })
            .join('') +
          `</div>`
        );
      })
      .join('');
    box.querySelectorAll<HTMLElement>('[data-rename]').forEach((b) => {
      b.addEventListener('click', () => {
        const id = b.dataset.rename!;
        const input = box.querySelector<HTMLInputElement>(`#name-${id}`)!;
        mutate(renameNode(s, id, input.value.trim()));
      });
    });
    box.querySelectorAll<HTMLElement>('[data-split]').forEach((b) => {
      b.addEventListener('click', () => {
        mutate(splitVisit(s, Number(b.dataset.split)));
      });
    });
  }

  function renderMerge(): void {
    const box = $('rv-merge-chips');
    const btn = $('rv-merge-btn') as HTMLButtonElement;
    const hint = $('rv-merge-hint');
    const labelOf = (id: string): string => {
      const n = s.nodes.find((x) => x.id === id);
      return n ? n.name || `户${n.autoNo}` : id;
    };
    if (s.nodes.length < 2) {
      box.innerHTML = '<p class="empty">至少需要两户才能合并</p>';
      btn.disabled = true;
      hint.textContent = '点选两户：第一个保留，第二个并入';
      return;
    }
    box.innerHTML = s.nodes
      .map(
        (n) =>
          `<button class="chip merge-chip" data-mid="${n.id}">${esc(labelOf(n.id))}</button>`,
      )
      .join('');
    const selected: string[] = [];
    const sync = (): void => {
      box.querySelectorAll<HTMLElement>('[data-mid]').forEach((x) => {
        x.classList.toggle('selected', selected.includes(x.dataset.mid!));
      });
      btn.disabled = selected.length !== 2;
      hint.textContent =
        selected.length === 0
          ? '点选两户：第一个保留，第二个并入'
          : selected.length === 1
            ? `已选「${labelOf(selected[0])}」· 再选一户`
            : `将把「${labelOf(selected[1])}」并入「${labelOf(selected[0])}」（名字/编号保留前者）`;
    };
    box.querySelectorAll<HTMLElement>('[data-mid]').forEach((b) => {
      b.addEventListener('click', () => {
        const id = b.dataset.mid!;
        const idx = selected.indexOf(id);
        if (idx >= 0) {
          selected.splice(idx, 1);
        } else {
          if (selected.length >= 2) selected.shift(); // 最多两个，新的顶掉最早的
          selected.push(id);
        }
        sync();
      });
    });
    btn.onclick = () => {
      if (selected.length === 2) {
        mutate(mergeNodes(s, selected[0], selected[1]));
      }
    };
    sync();
  }

  function renderJumps(): void {
    const box = $('rv-jumps');
    const jumps = s.points.filter((p) => p.jump);
    if (jumps.length === 0) {
      box.innerHTML = '<p class="empty">无异常跳变点 🎉</p>';
      return;
    }
    box.innerHTML =
      `<button id="rv-rmall" class="secondary small">剔除全部（${jumps.length} 个）</button>` +
      jumps
        .slice(0, 20)
        .map(
          (p) =>
            `<div class="jump-row"><span>${fmtClock(p.t)} · 精度 ${p.acc.toFixed(0)}m</span>` +
            `<button data-rm="${p.t}" class="secondary small">剔除</button></div>`,
        )
        .join('');
    $('rv-rmall').addEventListener('click', () => {
      let next = s;
      for (const p of s.points.filter((q) => q.jump)) {
        next = removePoint(next, p.t);
      }
      mutate(next);
    });
    box.querySelectorAll<HTMLElement>('[data-rm]').forEach((b) => {
      b.addEventListener('click', () => {
        mutate(removePoint(s, Number(b.dataset.rm)));
      });
    });
  }

  /** 漏访检查（F-9）：清单 vs 实际节点 */
  function renderPlanSection(): void {
    const box = $('rv-plan');
    if (!plan) {
      box.innerHTML =
        '<p class="empty">今年还没有清单。回记录页 →「📋 清单」先导入/添加。</p>';
      return;
    }
    if (plan.items.length === 0) {
      box.innerHTML = '<p class="empty">今年清单是空的。</p>';
      return;
    }
    const r = matchPlan(s, plan);
    if (r.missing.length === 0) {
      box.innerHTML = `<p>✅ 清单 ${plan.items.length} 户全部到访，没有漏拜！</p>`;
      return;
    }
    box.innerHTML =
      `<p class="missing">⚠️ 疑似漏访 ${r.missing.length} 户：</p>` +
      r.missing
        .map(
          (it) =>
            `<div class="jump-row"><span class="missing">❌ ${esc(it.name || '(未命名)')} 没去！</span>${it.pos ? '' : '<span class="visits">无位置·手动核对</span>'}</div>`,
        )
        .join('');
  }

  /** 套名继承（D17）：逐个节点弹去年候选，点击即套用 */
  function renderNameSection(): void {
    const box = $('rv-names');
    if (!prevSession || prevSession.nodes.length === 0) {
      box.innerHTML = '<p class="empty">没有往年记录可套用。</p>';
      return;
    }
    box.innerHTML =
      `<p class="hint">点击候选名立即套用到该户（按距离排序，取前 3）：</p>` +
      s.nodes
        .map((n) => {
          const cands = nameCandidates(n.pos, prevSession!.nodes, 3);
          const label = n.name || `户${n.autoNo}`;
          if (cands.length === 0) {
            return `<div class="jump-row"><span class="nlabel">${esc(label)}</span><span class="visits">无候选</span></div>`;
          }
          return (
            `<div class="jump-row"><span class="nlabel">${esc(label)}</span>` +
            cands
              .map(
                (c, k) =>
                  `<button data-apply="${n.id}" data-name="${esc(c.name)}" class="chip">${esc(c.name)}（${Math.round(c.distM)}m）</button>`,
              )
              .join('') +
            `</div>`
          );
        })
        .join('');
    box.querySelectorAll<HTMLElement>('[data-apply]').forEach((b) => {
      b.addEventListener('click', () => {
        mutate(renameNode(s, b.dataset.apply!, b.dataset.name!));
      });
    });
  }

  function renderProgress(): void {
    $('rv-progress').textContent = `${fmtDur(baseMs)} / ${fmtDur(pl.totalMs)} · ${speed}x`;
  }

  function tick(): void {
    const ms = Math.min(baseMs + (performance.now() - animStart) * speed, pl.totalMs);
    // P10：视图被销毁后（如播放中返回），元素可能已不存在——安全早退，不再续帧
    const prog = root.querySelector<HTMLElement>('#rv-progress');
    if (!prog) return;
    const track = root.querySelector<SVGPathElement>('#rv-trackplay');
    const dot = root.querySelector<SVGCircleElement>('#rv-dot');
    if (track) {
      track.style.strokeDasharray = '1';
      track.style.strokeDashoffset = String(1 - fractionAt(pl, ms));
    }
    const pos = positionAt(pl, ms);
    if (dot && pos) {
      dot.style.display = '';
      dot.setAttribute('cx', pos.x.toFixed(1));
      dot.setAttribute('cy', pos.y.toFixed(1));
    }
    prog.textContent = `${fmtDur(ms)} / ${fmtDur(pl.totalMs)} · ${speed}x`;
    if (ms >= pl.totalMs) {
      stopAnim();
      baseMs = pl.totalMs;
      return;
    }
    raf = requestAnimationFrame(tick);
  }

  $('rv-back').addEventListener('click', () => {
    stopAnim(); // P10：离开页面前停止动画，防 rAF 泄漏
    deps.onBack();
  });
  $('rv-opt').addEventListener('click', () => {
    stopAnim(); // P10：同上
    deps.onOptimize(s);
  });
  $('rv-play').addEventListener('click', () => {
    if (pl.pts.length === 0) return;
    if (playing) {
      baseMs = Math.min(baseMs + (performance.now() - animStart) * speed, pl.totalMs);
      stopAnim();
      return;
    }
    if (baseMs >= pl.totalMs) baseMs = 0; // 播完再点从头
    playing = true;
    animStart = performance.now();
    $('rv-play').textContent = '⏸ 暂停';
    raf = requestAnimationFrame(tick);
  });
  $('rv-reset').addEventListener('click', () => {
    baseMs = 0;
    stopAnim();
    tickOnce();
  });
  $('rv-s1').addEventListener('click', () => {
    speed = 1;
    renderProgress();
  });
  $('rv-s2').addEventListener('click', () => {
    speed = 2;
    renderProgress();
  });
  $('rv-s4').addEventListener('click', () => {
    speed = 4;
    renderProgress();
  });

  function tickOnce(): void {
    const ms = baseMs;
    const track = root.querySelector<SVGPathElement>('#rv-trackplay');
    if (track) {
      track.style.strokeDasharray = '1';
      track.style.strokeDashoffset = String(1 - fractionAt(pl, ms));
    }
    const pos = positionAt(pl, ms);
    const dot = root.querySelector<SVGCircleElement>('#rv-dot');
    if (dot && pos) {
      dot.style.display = '';
      dot.setAttribute('cx', pos.x.toFixed(1));
      dot.setAttribute('cy', pos.y.toFixed(1));
    }
    renderProgress();
  }

  refresh();

  // 异步加载今年清单与往年记录（漏访/套名依赖）
  void deps.loadPlan(s.year).then((p) => {
    plan = p;
    renderPlanSection();
  });
  void deps.loadPrev(s.year).then((p) => {
    prevSession = p;
    renderNameSection();
  });
}
