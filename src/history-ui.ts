/** 历史记录整页视图（从 main.ts 拆出）：列表 + 逐年成绩单 + 导入导出入口 */

import type { SessionData } from './state.js';
import { esc } from './dialog.js';

export interface HistoryDeps {
  onBack(): void;
  onDemo(): void;
  onExport(): void;
  onImportFile(file: File): void;
  onSelectSession(s: SessionData): void;
  listSessions(): Promise<SessionData[]>;
  statFor(s: SessionData): Promise<string>;
}

export async function mountHistoryView(root: HTMLElement, deps: HistoryDeps): Promise<void> {
  const sessions = (await deps.listSessions()).sort((a, b) => b.createdAt - a.createdAt);

  root.innerHTML = `
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
    </div>`;

  const $ = <T extends HTMLElement>(id: string): T => root.querySelector<T>('#' + id)!;

  $('h-back').addEventListener('click', () => deps.onBack());
  $('h-demo').addEventListener('click', () => deps.onDemo());
  $('h-export').addEventListener('click', () => deps.onExport());

  const fileInput = $<HTMLInputElement>('h-file');
  $('h-import').addEventListener('click', () => fileInput.click());
  fileInput.addEventListener('change', () => {
    const f = fileInput.files?.[0];
    if (f) deps.onImportFile(f);
    fileInput.value = '';
  });

  const list = $('h-list');
  const summary = $('h-summary');
  if (sessions.length === 0) {
    summary.innerHTML = '';
    list.innerHTML = '<p class="empty">还没有记录。大年初一，出发！</p>';
    return;
  }

  list.innerHTML = sessions
    .map(
      (s) =>
        `<button class="history-item" data-id="${esc(s.id)}">${esc(s.date)} · 计算中…</button>`,
    )
    .join('');

  list.querySelectorAll<HTMLElement>('[data-id]').forEach((b) => {
    b.addEventListener('click', () => {
      const sess = sessions.find((x) => x.id === b.dataset.id);
      if (sess) deps.onSelectSession(sess);
    });
  });

  const rows = await Promise.all(sessions.map(async (s) => ({ s, stat: await deps.statFor(s) })));
  rows.forEach(({ s, stat }) => {
    // E-10：按 dataset 比较而非拼选择器——id 已转义进属性，
    // 再拼进 querySelector 会被特殊字符破坏（防御一致性）
    const el = [...list.querySelectorAll<HTMLElement>('[data-id]')].find(
      (b) => b.dataset.id === s.id,
    );
    if (el) el.textContent = `${s.date} · ${stat}`;
  });
  renderYearSummary(summary, rows); // P15：历年成绩单 + 绕路率趋势线
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
