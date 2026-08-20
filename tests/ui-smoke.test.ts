// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mountUi } from '../src/ui.js';
import { mountPlanView } from '../src/plan-ui.js';
import { mountReviewView } from '../src/review-ui.js';
import { mountOptimizeView } from '../src/optimize-ui.js';
import { generateDemoSession } from '../src/demo.js';

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

describe('各视图挂载不抛错且关键按钮可用', () => {
  it('清单视图：返回 / 添加', async () => {
    const deps = {
      onBack: vi.fn(),
      loadPlan: vi.fn(async () => undefined),
      savePlan: vi.fn(async () => {}),
      listSessions: vi.fn(async () => []),
    };
    mountPlanView(makeRoot(), 2027, deps);
    document.querySelector<HTMLElement>('#pl-back')!.click();
    expect(deps.onBack).toHaveBeenCalledTimes(1);
    const promptSpy = vi.spyOn(window, 'prompt').mockReturnValue('张叔家');
    document.querySelector<HTMLElement>('#pl-add')!.click();
    expect(promptSpy).toHaveBeenCalledTimes(1);
    await Promise.resolve();
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
