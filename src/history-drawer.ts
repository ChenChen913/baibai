/** 记录页「往年历史」抽屉（从 ui.ts 拆出）：会话卡片列表 + 累计记录汇总 */

import type { SessionData } from './state.js';
import { ICONS } from './icons.js';
import { cnyLabel, fromDateStr } from './cny.js';
import { esc } from './dialog.js';

export function renderHistoryDrawer(
  listEl: HTMLElement,
  summaryEl: HTMLElement,
  sessions: SessionData[],
  statsMap: Map<string, string> | undefined,
  onSelect: (id: string) => void,
): void {
  if (sessions.length === 0) {
    summaryEl.innerHTML = '';
    listEl.innerHTML = `
      <div class="p-6 text-center space-y-2">
        <div class="text-2xl">🧧</div>
        <div class="text-xs font-bold text-stone-700">暂无往年拜年记录</div>
        <div class="text-[11px] text-stone-400">初一拜年结束后自动保存，也可点上方「生成演示数据」体验</div>
      </div>
    `;
    return;
  }

  // 渲染历史记录卡片
  listEl.innerHTML = sessions
    .map((s) => {
      const statText = statsMap?.get(s.id) || `${s.nodes.length} 户`;
      return `
        <div class="p-3.5 rounded-2xl bg-white border border-stone-200/90 shadow-xs space-y-2.5">
          <div class="flex justify-between items-center">
            <div class="font-bold text-stone-800 text-xs">${esc(s.date)} · ${esc(cnyLabel(fromDateStr(s.date)))}</div>
            <span class="text-[10px] font-bold text-red-700 bg-red-50 px-2 py-0.5 rounded">拜访 ${s.nodes.length} 户</span>
          </div>
          <div class="text-xs text-stone-600 bg-stone-50 p-2 rounded-xl">
            ${esc(statText)}
          </div>
          <div class="flex justify-end gap-2 pt-1">
            <button data-history-id="${esc(s.id)}" class="px-3 py-1.5 rounded-lg bg-red-600 text-white font-bold text-[11px] shadow-xs hover:bg-red-700 active:scale-95 transition flex items-center gap-1">
              ${ICONS.play} 轨迹回放与三线复盘
            </button>
          </div>
        </div>
      `;
    })
    .join('');

  listEl.querySelectorAll<HTMLElement>('[data-history-id]').forEach((btn) => {
    btn.addEventListener('click', () => {
      onSelect(btn.dataset.historyId!);
    });
  });

  // 历年绕路趋势
  summaryEl.innerHTML = `
    <div class="p-2.5 rounded-xl bg-amber-50/70 border border-amber-200/80 flex items-center justify-between text-xs text-amber-900">
      <span class="font-bold">📊 已累计记录 ${sessions.length} 年拜年轨迹</span>
      <span class="text-[10px] text-amber-700 bg-amber-100 px-2 py-0.5 rounded font-bold">算法 Held-Karp 精确解</span>
    </div>
  `;
}
