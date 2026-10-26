/** 记录页「拜年清单」抽屉（从 ui.ts 拆出，模板与事件绑定独立维护）
 *  E-11 内容指纹缓存放在工厂闭包里：每秒 render 只在数据真变时重建列表。 */

import type { SessionData } from './state.js';
import type { Plan } from './plan.js';
import { ICONS } from './icons.js';
import { esc } from './dialog.js';

export interface PlanDrawerEls {
  list: HTMLElement;
  badge: HTMLElement;
  dot: HTMLElement;
}

export interface PlanDrawer {
  render(session: SessionData | null, plan: Plan | null): void;
  /** 清单内容变化后强制下次重建 */
  invalidate(): void;
}

export function createPlanDrawer(
  els: PlanDrawerEls,
  onFocusNode?: (no: number) => void,
): PlanDrawer {
  let lastKey = '';

  function render(s: SessionData | null, plan: Plan | null): void {
    const planKey = [
      s?.state ?? '',
      s?.currentMode ?? '',
      s?.nodes.length ?? 0,
      s?.nodes.map((n) => `${n.id}:${n.name}:${n.autoNo}`).join(','),
      plan?.updatedAt ?? 0,
      plan?.items.length ?? 0,
    ].join('|');
    if (planKey === lastKey) return;
    lastKey = planKey;

    const { list: listEl, badge: badgeEl, dot: badgeDot } = els;
    const visitedCount = s?.nodes.length ?? 0;
    const planItems = plan?.items ?? [];
    const totalCount = planItems.length;

    badgeEl.textContent = `${visitedCount}/${totalCount > 0 ? totalCount : visitedCount} 已拜`;
    badgeDot.style.display = totalCount > visitedCount ? 'block' : 'none';

    if (totalCount === 0 && visitedCount === 0) {
      listEl.innerHTML = `
        <div class="p-4 text-center space-y-2">
          <div class="text-amber-600 text-2xl">📋</div>
          <div class="text-xs font-bold text-stone-700">暂无拜访清单</div>
          <div class="text-[11px] text-stone-400">可点击下方「导入去年」或「手动添加新拜访户」</div>
        </div>
      `;
      return;
    }

    let html = '';

    // 起点卡片
    html += `
      <div class="p-2.5 rounded-xl bg-amber-50 border border-amber-200 flex items-center justify-between shadow-xs">
        <div class="flex items-center gap-2">
          <div class="w-6 h-6 rounded-full bg-amber-500 text-white flex items-center justify-center font-bold text-[10px]">
            ${ICONS.house}
          </div>
          <div>
            <div class="font-bold text-xs text-stone-800">起点：自家庭院</div>
            <div class="text-[10px] text-stone-400">大年初一 出发点</div>
          </div>
        </div>
        <span class="text-[10px] font-bold text-amber-700 bg-amber-100 px-2 py-0.5 rounded">出发点</span>
      </div>
    `;

    // 已拜访各户 (nodes)
    if (s && s.nodes.length > 0) {
      s.nodes.forEach((n, idx) => {
        const isCurrent = s.state === 'PAUSED' && idx === s.nodes.length - 1;
        const name = n.name || `第 ${n.autoNo} 户`;

        if (isCurrent) {
          html += `
            <div class="p-2.5 rounded-xl bg-red-50 border border-red-200 flex items-center justify-between shadow-xs">
              <div class="flex items-center gap-2">
                <div class="w-6 h-6 rounded-full bg-red-600 text-white flex items-center justify-center font-bold text-[10px] animate-pulse">
                  ${n.autoNo}
                </div>
                <div>
                  <div class="font-bold text-xs text-red-700">${n.autoNo}. ${esc(name)} (当前停留点)</div>
                  <div class="text-[10px] text-stone-500">正在喝茶拜年中…</div>
                </div>
              </div>
              <span class="text-[10px] font-bold text-red-700 bg-red-100 px-2 py-0.5 rounded">当前位置</span>
            </div>
          `;
        } else {
          html += `
            <div class="p-2.5 rounded-xl bg-white border border-stone-200/80 flex items-center justify-between shadow-xs">
              <div class="flex items-center gap-2">
                <div class="w-6 h-6 rounded-full bg-emerald-500 text-white flex items-center justify-center font-bold text-[10px]">
                  ${ICONS.check}
                </div>
                <div>
                  <div class="font-bold text-xs text-stone-500 line-through">${n.autoNo}. ${esc(name)}</div>
                  <div class="text-[10px] text-stone-400">已顺利完成拜访</div>
                </div>
              </div>
              <span class="text-[10px] font-bold text-emerald-700 bg-emerald-50 px-2 py-0.5 rounded">已拜访</span>
            </div>
          `;
        }
      });
    }

    // 未拜访清单项
    if (planItems.length > visitedCount) {
      for (let i = visitedCount; i < planItems.length; i++) {
        const item = planItems[i];
        const num = i + 1;
        html += `
          <div class="p-2.5 rounded-xl bg-white border border-stone-200 flex items-center justify-between shadow-xs">
            <div class="flex items-center gap-2">
              <div class="w-6 h-6 rounded-full bg-stone-200 text-stone-700 flex items-center justify-center font-bold text-[10px]">
                ${num}
              </div>
              <div>
                <div class="font-bold text-xs text-stone-800">${num}. ${esc(item.name)}</div>
                <div class="text-[10px] text-stone-400">待拜访 · 路线已优化规划</div>
              </div>
            </div>
            <button data-focus-node="${num}" class="text-[10px] font-bold text-red-600 border border-red-200 bg-red-50 hover:bg-red-100 px-2 py-1 rounded transition">
              定位导航
            </button>
          </div>
        `;
      }
    }

    listEl.innerHTML = html;

    listEl.querySelectorAll<HTMLElement>('[data-focus-node]').forEach((btn) => {
      btn.addEventListener('click', () => {
        onFocusNode?.(Number(btn.dataset.focusNode));
      });
    });
  }

  return {
    render,
    invalidate(): void {
      lastKey = '';
    },
  };
}
