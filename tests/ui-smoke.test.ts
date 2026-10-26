// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mountUi, type UiCallbacks } from '../src/ui.js';
import { mountPlanView } from '../src/plan-ui.js';
import { mountReviewView } from '../src/review-ui.js';
import { mountOptimizeView } from '../src/optimize-ui.js';
import { generateDemoSession } from '../src/demo.js';
import type { Plan } from '../src/plan.js';

function makeRoot(): HTMLElement {
  document.body.innerHTML = '<div id="app"></div>';
  return document.querySelector<HTMLElement>('#app')!;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('记录页事件绑定（回归：曾因 querySelector 缺 # 导致全按钮失灵）', () => {
  it('挂载后每个按钮点击都触发对应回调', () => {
    const cb = {
      onStart: vi.fn(),
      onPause: vi.fn(),
      onResume: vi.fn(),
      onFinish: vi.fn(),
      onMode: vi.fn(),
      onExport: vi.fn(),
      onHistory: vi.fn(),
      onPlan: vi.fn(),
      onFeedbackToggle: vi.fn(),
      onDatePick: vi.fn(),
      onPreload: vi.fn(),
    };
    const ui = mountUi(makeRoot(), cb);
    const click = (id: string) => {
      const el = document.querySelector<HTMLButtonElement>(`#${id}`);
      expect(el, `按钮 #${id} 应存在`).toBeTruthy();
      el!.click();
    };
    click('btn-start');
    expect(cb.onStart).toHaveBeenCalledTimes(1);
    click('btn-pause');
    expect(cb.onPause).toHaveBeenCalledTimes(1);
    click('btn-resume');
    expect(cb.onResume).toHaveBeenCalledTimes(1);
    click('btn-finish');
    expect(cb.onFinish).toHaveBeenCalledTimes(1);
    click('btn-export');
    expect(cb.onExport).toHaveBeenCalledTimes(1);
    click('btn-walk');
    expect(cb.onMode).toHaveBeenCalledWith('walk');
    click('btn-bike');
    expect(cb.onMode).toHaveBeenCalledWith('bike');
    click('btn-plan');
    expect(cb.onPlan).toHaveBeenCalledTimes(1);
    click('btn-history');
    expect(cb.onHistory).toHaveBeenCalledTimes(1);
    click('btn-date');
    expect(cb.onDatePick).toHaveBeenCalledTimes(1);
    click('map-preload');
    expect(cb.onPreload).toHaveBeenCalledTimes(1);
    ui.render(null, 0);
  });

  it('无会话时走路/骑车禁用；记录中可用；结束后禁用', () => {
    const ui = mountUi(makeRoot(), {
      onStart: () => {},
      onPause: () => {},
      onResume: () => {},
      onFinish: () => {},
      onMode: () => {},
      onExport: () => {},
      onHistory: () => {},
      onPlan: () => {},
      onFeedbackToggle: () => {},
      onDatePick: () => {},
      onPreload: () => {},
    });
    const walk = () => document.querySelector<HTMLButtonElement>('#btn-walk')!;
    const bike = () => document.querySelector<HTMLButtonElement>('#btn-bike')!;

    ui.render(null, 0);
    expect(walk().disabled).toBe(true);
    expect(bike().disabled).toBe(true);

    const snap = generateDemoSession();
    ui.render({ ...snap, state: 'WALKING' }, 0);
    expect(walk().disabled).toBe(false);
    expect(bike().disabled).toBe(false);

    ui.render({ ...snap, state: 'FINISHED' }, 0);
    expect(walk().disabled).toBe(true);
    expect(bike().disabled).toBe(true);
  });
});

describe('抽屉渲染（历史/清单拆模块后的回归）', () => {
  function mountWith(overrides: Partial<UiCallbacks> = {}) {
    return mountUi(makeRoot(), {
      onStart: () => {},
      onPause: () => {},
      onResume: () => {},
      onFinish: () => {},
      onMode: () => {},
      onExport: () => {},
      onHistory: () => {},
      onPlan: () => {},
      onFeedbackToggle: () => {},
      onDatePick: () => {},
      onPreload: () => {},
      ...overrides,
    });
  }

  it('历史抽屉：id 转义进属性，点击回传原 id', () => {
    const onSelectHistory = vi.fn();
    const ui = mountWith({ onSelectHistory });
    const demo = generateDemoSession();
    demo.id = 's"1<';
    ui.openDrawer('history');
    ui.setHistorySessions([demo], new Map([[demo.id, '8 户 · 1.00 km']]));
    const list = document.querySelector<HTMLElement>('#drawer-history-list')!;
    expect(list.textContent).toContain(demo.date);
    expect(list.textContent).toContain('8 户 · 1.00 km');
    const btn = list.querySelector<HTMLElement>('[data-history-id]')!;
    expect(btn.dataset.historyId).toBe('s"1<'); // 属性转义后 dataset 还原原值
    btn.click();
    expect(onSelectHistory).toHaveBeenCalledWith('s"1<');
  });

  it('清单抽屉：户名/清单名转义，定位按钮回传序号', () => {
    const onFocusNode = vi.fn();
    const ui = mountWith({ onFocusNode });
    const demo = generateDemoSession();
    demo.nodes[0].name = '<img src=x onerror=alert(1)>';
    const items = Array.from({ length: 10 }, (_, i) => ({
      name: i === 9 ? '"><script>x</script>' : `清单户 ${i + 1}`,
      pos: null,
    }));
    ui.setPlan({ year: 2026, createdAt: 0, updatedAt: Date.now(), items });
    ui.render({ ...demo, state: 'WALKING' }, 0);
    const list = document.querySelector<HTMLElement>('#drawer-plan-list')!;
    expect(list.querySelectorAll('img, script').length).toBe(0); // 未注入元素
    expect(list.textContent).toContain('<img src=x onerror=alert(1)>'); // 以纯文本呈现
    expect(list.textContent).toContain('"><script>x</script>');
    const focusBtn = list.querySelector<HTMLElement>('[data-focus-node]')!;
    focusBtn.click();
    expect(onFocusNode).toHaveBeenCalledWith(9); // 第 9 户（8 户已访后的第一个清单项）
  });
});

describe('各视图挂载不抛错且关键按钮可用', () => {
  it('清单视图：返回 / 添加（应用内对话框，不再用 window.prompt）', async () => {
    const savePlan = vi.fn(async (_p: Plan) => {});
    const deps = {
      onBack: vi.fn(),
      loadPlan: vi.fn(async () => undefined),
      savePlan,
      listSessions: vi.fn(async () => []),
    };
    const root = makeRoot();
    mountPlanView(root, 2027, deps);
    document.querySelector<HTMLElement>('#pl-back')!.click();
    expect(deps.onBack).toHaveBeenCalledTimes(1);

    // E-16：添加一户改走应用内对话框（与安卓端 Compose 对话框观感一致）
    document.querySelector<HTMLElement>('#pl-add')!.click();
    await Promise.resolve();
    const overlay = document.querySelector<HTMLElement>('.dlg-overlay');
    expect(overlay, '应弹出应用内对话框').toBeTruthy();
    const input = overlay!.querySelector<HTMLInputElement>('input')!;
    expect(input).toBeTruthy();
    input.value = '张叔家';
    overlay!.querySelector<HTMLButtonElement>('button.primary')!.click();
    await Promise.resolve();
    await Promise.resolve();

    expect(savePlan).toHaveBeenCalledTimes(1);
    const saved = savePlan.mock.calls[0][0];
    expect(saved.items).toHaveLength(1);
    expect(saved.items[0].name).toBe('张叔家');
    expect(document.querySelector('.dlg-overlay'), '确定后对话框应关闭').toBeNull();
  });

  it('回顾视图：三线对比入口与返回', () => {
    const deps = {
      onBack: vi.fn(),
      onSave: vi.fn(),
      onOptimize: vi.fn(),
      loadPlan: vi.fn(async () => undefined),
      loadPrev: vi.fn(async () => undefined),
    };
    mountReviewView(makeRoot(), generateDemoSession(), deps);
    document.querySelector<HTMLElement>('#rv-opt')!.click();
    expect(deps.onOptimize).toHaveBeenCalledTimes(1);
    document.querySelector<HTMLElement>('#rv-back')!.click();
    expect(deps.onBack).toHaveBeenCalledTimes(1);
  });

  // 改名接线回归：节点 id 会进 HTML 属性，含引号时不得破坏属性结构，且改名必须命中正确节点
  it('回顾视图：户名转义且改名命中正确节点', () => {
    const demo = generateDemoSession();
    demo.nodes[0].id = 'n"1';
    demo.nodes[0].name = '<b>坏名字</b>';
    const deps = {
      onBack: vi.fn(),
      onSave: vi.fn(),
      onOptimize: vi.fn(),
      loadPlan: vi.fn(async () => undefined),
      loadPrev: vi.fn(async () => undefined),
    };
    mountReviewView(makeRoot(), demo, deps);
    const rows = document.querySelectorAll<HTMLElement>('#rv-nodes .node-row');
    const input = rows[0].querySelector<HTMLInputElement>('input')!;
    expect(input.value).toBe('<b>坏名字</b>'); // 转义写入后 DOM 还原为纯文本
    expect(document.querySelectorAll('#rv-nodes b, #rv-nodes img').length).toBe(0);

    input.value = '测试户';
    rows[0].querySelector<HTMLElement>('[data-rename]')!.click();
    expect(deps.onSave).toHaveBeenCalledTimes(1);
    const saved = deps.onSave.mock.calls[0][0] as typeof demo;
    expect(saved.nodes[0].id).toBe('n"1'); // 命中的是首个节点，而不是被拆坏的选择器
    expect(saved.nodes[0].name).toBe('测试户');
  });

  it('回顾视图：合并交互——点选两户后按钮可用并触发保存', () => {
    const deps = {
      onBack: vi.fn(),
      onSave: vi.fn(),
      onOptimize: vi.fn(),
      loadPlan: vi.fn(async () => undefined),
      loadPrev: vi.fn(async () => undefined),
    };
    mountReviewView(makeRoot(), generateDemoSession(), deps);
    const chips = document.querySelectorAll<HTMLElement>('.merge-chip');
    expect(chips.length).toBe(8); // demo 8 户
    const btn = document.querySelector<HTMLButtonElement>('#rv-merge-btn')!;
    expect(btn.disabled).toBe(true);
    chips[0].click();
    expect(btn.disabled).toBe(true); // 只选了一个
    chips[1].click();
    expect(btn.disabled).toBe(false);
    expect(chips[0].classList.contains('selected')).toBe(true);
    expect(chips[1].classList.contains('selected')).toBe(true);
    btn.click();
    expect(deps.onSave).toHaveBeenCalledTimes(1); // 合并触发保存
    expect(document.querySelectorAll('.merge-chip').length).toBe(7); // 8 → 7 户
  });

  it('回顾视图：家/户标记不叠在一点（单点投影回归防护）', () => {
    const deps = {
      onBack: vi.fn(),
      onSave: vi.fn(),
      onOptimize: vi.fn(),
      loadPlan: vi.fn(async () => undefined),
      loadPrev: vi.fn(async () => undefined),
    };
    mountReviewView(makeRoot(), generateDemoSession(), deps);
    const pts = [...document.querySelectorAll('#rv-svg .node circle')].map(
      (c) => c.getAttribute('cx') + ',' + c.getAttribute('cy'),
    );
    expect(new Set(pts).size).toBeGreaterThanOrEqual(5); // demo 9 个标记应有不同坐标
  });

  it('三线对比视图：节点标记不叠在一点（单点投影回归防护）', () => {
    vi.stubGlobal('requestAnimationFrame', () => 1);
    vi.stubGlobal('cancelAnimationFrame', () => {});
    const deps = { onBack: vi.fn() };
    mountOptimizeView(makeRoot(), generateDemoSession(), deps);
    const pts = [...document.querySelectorAll('#opt-svg g.node circle')].map(
      (c) => c.getAttribute('cx') + ',' + c.getAttribute('cy'),
    );
    expect(new Set(pts).size).toBeGreaterThanOrEqual(5); // demo 8 户 + 家应有不同坐标
  });

  it('三线对比视图：切标签与推演不抛错', () => {
    let rafCb: FrameRequestCallback | null = null;
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
      rafCb = cb;
      return 1;
    });
    vi.stubGlobal('cancelAnimationFrame', () => {
      rafCb = null;
    });
    const deps = { onBack: vi.fn() };
    mountOptimizeView(makeRoot(), generateDemoSession(), deps);
    document.querySelector<HTMLElement>('#opt-tab-fly')!.click();
    document.querySelector<HTMLElement>('#opt-tab-time')!.click();
    document.querySelector<HTMLElement>('#opt-reveal')!.click(); // 推演启动，不抛错
    expect(rafCb).not.toBeNull();
    document.querySelector<HTMLElement>('#opt-morph')!.click(); // 压轴启动，不抛错
    document.querySelector<HTMLElement>('#opt-back')!.click();
    expect(deps.onBack).toHaveBeenCalledTimes(1);
  });
});
