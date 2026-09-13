/** 今年清单管理视图（M4/F-1）：从去年导入、增删改名。UI 层，验收走浏览器。 */

import type { Plan } from './plan.js';
import { planFromSession } from './plan.js';
import type { SessionData } from './state.js';
// E-16：应用内对话框 + 公共转义函数（替代 window.alert/confirm/prompt，
// 与安卓端 Compose 自绘对话框保持一致的观感）
import { alertDialog, confirmDialog, esc, promptDialog } from './dialog.js';

export interface PlanDeps {
  onBack(): void;
  loadPlan(year: number): Promise<Plan | undefined>;
  savePlan(p: Plan): Promise<void>;
  listSessions(): Promise<SessionData[]>;
}

export function mountPlanView(root: HTMLElement, year: number, deps: PlanDeps): void {
  let plan: Plan = { year, items: [], createdAt: Date.now(), updatedAt: Date.now() };

  root.innerHTML = `
    <div class="wrap">
      <div class="bar">
        <button id="pl-back" class="secondary small">← 返回</button>
        <div class="bar-title">${year} 年拜年清单</div>
      </div>
      <p class="hint">出门前看一眼，拜年时照常只按「暂停」，回来系统自动对比漏了谁。</p>
      <div class="pctrl">
        <button id="pl-import" class="small">📥 从去年导入</button>
        <button id="pl-add" class="secondary small">＋ 添加一户</button>
      </div>
      <div id="pl-list" class="history-list"></div>
    </div>
  `;

  const $ = (id: string): HTMLElement => root.querySelector<HTMLElement>('#' + id)!;

  function persist(): void {
    plan.updatedAt = Date.now();
    // E-17：写盘失败必须让用户知道（原先 loadPlan 无兜底会崩、savePlan 静默失败）
    void deps.savePlan(plan).catch((e: unknown) => {
      window.console.warn('[plan]', e);
      void alertDialog(root, '保存失败', '清单没能保存到本机，请检查存储空间后重试。');
    });
  }

  function render(): void {
    const list = $('pl-list');
    list.innerHTML =
      plan.items.length === 0
        ? '<p class="empty">清单为空：点「从去年导入」或手动添加</p>'
        : plan.items
            .map(
              (it, i) => `
              <div class="node-row">
                <span class="nlabel">${i + 1}</span>
                <input id="pi-name-${i}" value="${esc(it.name)}" placeholder="户名"/>
                <button data-rename="${i}" class="secondary small">改名</button>
                <button data-del="${i}" class="secondary small">删除</button>
                ${it.pos ? '' : '<span class="visits">无位置·手动核对</span>'}
              </div>`,
            )
            .join('');
    list.querySelectorAll<HTMLElement>('[data-rename]').forEach((b) => {
      b.addEventListener('click', () => {
        const i = Number(b.dataset.rename);
        const input = list.querySelector<HTMLInputElement>(`#pi-name-${i}`)!;
        plan.items[i] = { ...plan.items[i], name: input.value.trim() };
        persist();
        render();
      });
    });
    list.querySelectorAll<HTMLElement>('[data-del]').forEach((b) => {
      b.addEventListener('click', () => {
        plan.items.splice(Number(b.dataset.del), 1);
        persist();
        render();
      });
    });
  }

  $('pl-back').addEventListener('click', () => deps.onBack());
  $('pl-import').addEventListener('click', async () => {
    const sessions = (await deps.listSessions()).sort(
      (a, b) => b.createdAt - a.createdAt,
    );
    // P16：只取真正的往年记录；无往年记录时不得拿当年自己的记录冒充"去年"
    const prev = sessions.find((x) => x.year < year);
    if (!prev) {
      await alertDialog(root, '无法导入', '还没有历史拜年记录，先去拜一次年吧。');
      return;
    }
    const ok = await confirmDialog(
      root,
      '从去年导入',
      `从 ${prev.date} 的 ${prev.nodes.length} 户生成清单？（会覆盖当前清单）`,
      '导入',
      '取消',
    );
    if (ok) {
      plan.items = planFromSession(prev, year).items;
      persist();
      render();
    }
  });
  $('pl-add').addEventListener('click', async () => {
    const name = await promptDialog(
      root,
      '添加一户',
      '这户叫什么？（留空则不加，之后也可以再改名）',
      '',
      '如：大伯家',
    );
    if (name === null) return;
    plan.items.push({ name, pos: null });
    persist();
    render();
  });

  void deps.loadPlan(year).then((p) => {
    if (p) plan = p;
    render();
  });
}
