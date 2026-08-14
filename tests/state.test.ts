import { describe, expect, it } from 'vitest';
import {
  RecorderState,
  Checkpoint,
  HOME_ID,
  MERGE_THRESHOLD_M,
} from '../src/state.js';
import { Fix } from '../src/geo.js';

const HOME = { lat: 31.0, lng: 121.0 };
const R = 6371000;
/** 从 Home 向北/东偏移 m 米 */
const far = (m: number, dir: 'n' | 'e' = 'n') => {
  if (dir === 'n') return { lat: HOME.lat + ((m / R) * 180) / Math.PI, lng: HOME.lng };
  return {
    lat: HOME.lat,
    lng: HOME.lng + ((m / R) * 180) / Math.PI / Math.cos((HOME.lat * Math.PI) / 180),
  };
};
const fix = (pos: { lat: number; lng: number }, acc = 5): Fix => ({ pos, acc });
const fixes = (pos: { lat: number; lng: number }, acc = 5): Fix[] => [fix(pos, acc)];
const T0 = 1_700_000_000_000;

function started(): RecorderState {
  const r = new RecorderState();
  r.start(fixes(HOME), T0);
  return r;
}

describe('状态机转移', () => {
  it('完整闭环：开始→暂停→继续→暂停→结束', () => {
    const r = started();
    const a = r.pause(fixes(far(100)), T0 + 1000);
    expect(a.autoNo).toBe(1);
    r.resume(T0 + 2000);
    const b = r.pause(fixes(far(300)), T0 + 3000);
    expect(b.autoNo).toBe(2);
    const res = r.finish(fixes(HOME), T0 + 4000);
    expect(res).toEqual({ ok: true });
    expect(r.state).toBe('FINISHED');
    expect(r.snapshot.finished).toBe(true);
  });

  it('非法转移被拒绝', () => {
    const r = new RecorderState();
    expect(() => r.pause(fixes(HOME), T0)).toThrow(/非法转移/);
    expect(() => r.resume(T0)).toThrow(/非法转移/);
    expect(() => r.finish(fixes(HOME), T0)).toThrow(/非法转移/);
    r.start(fixes(HOME), T0);
    expect(() => r.start(fixes(HOME), T0 + 1)).toThrow(/非法转移/);
  });

  it('SPEC 修正：WALKING 状态可直接结束（到家直接按结束）', () => {
    const r = started();
    r.pause(fixes(far(100)), T0 + 1000);
    r.resume(T0 + 2000); // 回到 WALKING
    const res = r.finish(fixes(HOME), T0 + 3000);
    expect(res).toEqual({ ok: true });
  });

  it('无有效定位时 start 抛错；有 fallback 时可用', () => {
    const r1 = new RecorderState();
    expect(() => r1.start([], T0)).toThrow(/无有效定位/);
    const r2 = new RecorderState();
    r2.start([], T0, fix(HOME));
    expect(r2.snapshot.home).toEqual(HOME);
  });

  it('无有效定位时 pause 抛错', () => {
    const r = started();
    expect(() => r.pause([], T0 + 1000)).toThrow(/无有效定位/);
  });
});

describe('10m 合并（D10）', () => {
  it('8m 内重复暂停合并到同一节点', () => {
    const r = started();
    const a = r.pause(fixes(far(100)), T0 + 1000);
    r.resume(T0 + 2000);
    const b = r.pause(fixes(far(108)), T0 + 3000); // 与 A 距 8m
    expect(b.id).toBe(a.id);
    expect(r.snapshot.nodes).toHaveLength(1);
    expect(r.snapshot.visits).toHaveLength(2);
  });

  it('边界：9.999m 合并、10.001m 新建', () => {
    const r = started();
    const a = r.pause(fixes(far(100)), T0 + 1000);
    r.resume(T0 + 2000);
    const bNear = r.pause(fixes(far(100 + MERGE_THRESHOLD_M - 0.001)), T0 + 3000);
    expect(bNear.id).toBe(a.id);
    r.resume(T0 + 4000);
    const cFar = r.pause(fixes(far(100 + MERGE_THRESHOLD_M + 0.001)), T0 + 5000);
    expect(cFar.id).not.toBe(a.id);
    expect(r.snapshot.nodes).toHaveLength(2);
  });

  it('低精度 fix 不参与中位数，节点标 lowAcc', () => {
    const r = started();
    const n = r.pause(
      [
        { pos: far(100), acc: 80 },
        { pos: far(200), acc: 90 }, // 全部低精度 → 用原始最后一点
      ],
      T0 + 1000,
    );
    expect(n.lowAcc).toBe(true);
    expect(n.pos).toEqual(far(200));
  });

  it('高精度优先：低精度点被过滤后取高精度中位数', () => {
    const r = started();
    const n = r.pause(
      [
        { pos: far(100), acc: 8 },
        { pos: far(120), acc: 90 }, // 被过滤
        { pos: far(110), acc: 9 },
      ],
      T0 + 1000,
    );
    expect(n.lowAcc).toBeUndefined();
    expect(n.pos).toEqual(far(100)); // 8/9 精度两点取中偏前
  });

  it('5m 内暂停合并到 Home（D20 中途回家）', () => {
    const r = started();
    const n = r.pause(fixes(far(5)), T0 + 1000);
    expect(n.id).toBe(HOME_ID);
    expect(r.snapshot.nodes).toHaveLength(0);
    expect(r.snapshot.visits[0].nodeId).toBe(HOME_ID);
  });
});

describe('自动编号（D11）', () => {
  it('按拜访顺序编号，不含 Home', () => {
    const r = started();
    expect(r.pause(fixes(far(100)), T0 + 1).autoNo).toBe(1);
    r.resume(T0 + 2);
    expect(r.pause(fixes(far(300)), T0 + 3).autoNo).toBe(2);
    r.resume(T0 + 4);
    expect(r.pause(fixes(far(500)), T0 + 5).autoNo).toBe(3);
  });

  it('撤销后新建节点复用编号', () => {
    const r = started();
    r.pause(fixes(far(100)), T0 + 1);
    r.resume(T0 + 2);
    r.pause(fixes(far(300)), T0 + 3); // 2 号
    r.undo(); // 撤销 2 号
    r.pause(fixes(far(400)), T0 + 4);
    expect(r.snapshot.nodes.map((n) => n.autoNo)).toEqual([1, 2]);
  });
});

describe('撤销（D19 R2）', () => {
  it('撤销链回溯到待机', () => {
    const r = started();
    r.pause(fixes(far(100)), T0 + 1000); // A
    r.resume(T0 + 2000);
    r.pause(fixes(far(300)), T0 + 3000); // B
    expect(r.undo()).toBe(true); // 撤 B
    expect(r.state).toBe('WALKING');
    expect(r.snapshot.nodes).toHaveLength(1);
    expect(r.undo()).toBe(true); // 撤 continue
    expect(r.state).toBe('PAUSED');
    expect(r.snapshot.visits[0].leaveT).toBeNull();
    expect(r.undo()).toBe(true); // 撤 A
    expect(r.state).toBe('WALKING');
    expect(r.snapshot.nodes).toHaveLength(0);
    expect(r.undo()).toBe(true); // 撤开始
    expect(r.state).toBe('IDLE');
    expect(r.snapshot.nodes).toHaveLength(0);
    expect(r.snapshot.visits).toHaveLength(0);
  });

  it('撤销"合并到已有节点"的暂停：只删访问，不删节点', () => {
    const r = started();
    const a = r.pause(fixes(far(100)), T0 + 1000);
    r.resume(T0 + 2000);
    r.pause(fixes(far(108)), T0 + 3000); // 合并到 A
    expect(r.snapshot.visits).toHaveLength(2);
    r.undo();
    expect(r.snapshot.visits).toHaveLength(1);
    expect(r.snapshot.nodes).toHaveLength(1);
    expect(r.snapshot.nodes[0].id).toBe(a.id);
  });

  it('撤销结束：回到结束前状态', () => {
    const r = started();
    r.pause(fixes(far(100)), T0 + 1000);
    r.resume(T0 + 2000);
    r.finish(fixes(HOME), T0 + 3000);
    expect(r.undo()).toBe(true);
    expect(r.state).toBe('WALKING'); // 结束前是 WALKING
    expect(r.snapshot.finished).toBe(false);
  });

  it('无可撤销时返回 false', () => {
    const r = new RecorderState();
    expect(r.undo()).toBe(false);
  });
});

describe('Home 起止（D9/D10）', () => {
  it('距 Home 15m 结束自动通过（FINISH_OK_M=20）', () => {
    const r = started();
    r.pause(fixes(far(100)), T0 + 1000);
    r.resume(T0 + 2000);
    const res = r.finish(fixes(far(15)), T0 + 3000);
    expect(res).toEqual({ ok: true });
    expect(r.state).toBe('FINISHED');
  });

  it('距 Home 500m 结束被拒并可强制', () => {
    const r = started();
    r.pause(fixes(far(100)), T0 + 1000);
    r.resume(T0 + 2000);
    const res = r.finish(fixes(far(500)), T0 + 3000);
    expect(res.ok).toBe(false);
    expect(Math.round((res as { distM: number }).distM)).toBe(500);
    expect(r.state).toBe('WALKING');
    const forced = r.finish(fixes(far(500)), T0 + 4000, true);
    expect(forced).toEqual({ ok: true });
    expect(r.state).toBe('FINISHED');
  });
});

describe('跳变防护（D22 最小版）', () => {
  it('2s 内 500m 跳变标 jump，超时不算', () => {
    const r = started();
    r.addPoint(HOME, 5, T0);
    const p1 = r.addPoint(far(500), 5, T0 + 1000);
    expect(p1.jump).toBe(true);
    const p2 = r.addPoint(far(600), 5, T0 + 5000);
    expect(p2.jump).toBeUndefined();
  });

  it('非 WALKING 状态记点抛错', () => {
    const r = new RecorderState();
    expect(() => r.addPoint(HOME, 5, T0)).toThrow(/非法转移/);
  });
});

describe('出行方式（D19 R1）', () => {
  it('骑车段访问记为 bike，到户自动回走路', () => {
    const r = started();
    r.setMode('bike', T0 + 500);
    r.pause(fixes(far(100)), T0 + 1000);
    expect(r.snapshot.visits[0].mode).toBe('bike');
    expect(r.snapshot.currentMode).toBe('walk'); // D19：到户自动回走路
    r.resume(T0 + 2000);
    r.pause(fixes(far(300)), T0 + 3000);
    expect(r.snapshot.visits[1].mode).toBe('walk');
  });

  it('撤销暂停时还原出行方式', () => {
    const r = started();
    r.setMode('bike', T0 + 500);
    r.pause(fixes(far(100)), T0 + 1000);
    expect(r.snapshot.currentMode).toBe('walk');
    r.undo();
    expect(r.snapshot.currentMode).toBe('bike'); // 回到"仍在骑行前往"的状态
  });
});

describe('检查点与恢复（D22）', () => {
  it('JSON 往返后快照一致', () => {
    const r = started();
    r.pause(fixes(far(100)), T0 + 1000);
    r.resume(T0 + 2000);
    r.addPoint(far(50), 5, T0 + 2100);
    const ck = r.checkpoint();
    const json = JSON.parse(JSON.stringify(ck)) as Checkpoint;
    const r2 = RecorderState.restore(json);
    expect(r2.snapshot).toEqual(ck.session);
  });

  it('恢复后可继续记录且撤销历史可用', () => {
    const r = started();
    r.pause(fixes(far(100)), T0 + 1000);
    const ck = r.checkpoint();
    const r2 = RecorderState.restore(ck);
    expect(r2.state).toBe('PAUSED');
    r2.resume(T0 + 2000);
    expect(r2.state).toBe('WALKING');
    expect(r2.undo()).toBe(true); // 恢复后仍可撤销（动作历史随检查点保存）
    expect(r2.state).toBe('PAUSED');
  });
});
