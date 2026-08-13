/** 回顾页：轨迹回放 + 收拾（改名/合并/拆分/剔除异常点）。UI 层，验收走浏览器/真机。 */

import type { SessionData } from './state.js';
import { buildEdges, projectToView } from './track.js';
import { buildPlan, fractionAt, positionAt, type PlaybackPlan } from './playback.js';
import { mergeNodes, removePoint, renameNode, splitVisit } from './review.js';

export interface ReviewDeps {
  onBack(): void;
  onSave(s: SessionData): void;
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

  root.innerHTML = `
    <div class="wrap">
      <div class="bar">
        <button id="rv-back" class="secondary small">← 返回</button>
        <div class="bar-title" id="rv-title"></div>
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
        <div class="merge-row">
          <select id="rv-m-keep"></select>
          <span>＋</span>
          <select id="rv-m-drop"></select>
          <button id="rv-merge" class="small">合并</button>
        </div>
        <h2>异常跳变点</h2>
        <div id="rv-jumps"></div>
      </div>
    </div>
  `;

  const $ = (id: string): HTMLElement => root.querySelector<HTMLElement>(id)!;

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
    const keep = $('rv-m-keep') as HTMLSelectElement;
    const drop = $('rv-m-drop') as HTMLSelectElement;
    const label = (n: { id: string; name: string; autoNo: number }) =>
      `${n.name || `户${n.autoNo}`}`;
    keep.innerHTML = s.nodes
      .map((n) => `<option value="${n.id}">${esc(label(n))}</option>`)
      .join('');
    drop.innerHTML = s.nodes
      .map((n) => `<option value="${n.id}">${esc(label(n))}</option>`)
      .join('');
    $('rv-merge').addEventListener('click', () => {
      if (s.nodes.length < 2) return;
      if (keep.value === drop.value) {
        window.alert('请选择两个不同的户');
        return;
      }
      mutate(mergeNodes(s, keep.value, drop.value));
    });
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

  function renderProgress(): void {
    $('rv-progress').textContent = `${fmtDur(baseMs)} / ${fmtDur(pl.totalMs)} · ${speed}x`;
  }

  function tick(): void {
    const ms = Math.min(baseMs + (performance.now() - animStart) * speed, pl.totalMs);
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
    $('rv-progress').textContent = `${fmtDur(ms)} / ${fmtDur(pl.totalMs)} · ${speed}x`;
    if (ms >= pl.totalMs) {
      stopAnim();
      baseMs = pl.totalMs;
      return;
    }
    raf = requestAnimationFrame(tick);
  }

  $('rv-back').addEventListener('click', () => deps.onBack());
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
}
