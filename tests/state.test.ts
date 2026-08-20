import { describe, expect, it } from 'vitest';
import {
  RecorderState,
  Checkpoint,
  HOME_ID,
  MERGE_THRESHOLD_M,
} from '../src/state.js';
import { Fix, haversineM } from '../src/geo.js';

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
    // R7：跳变点直接丢弃——返回值带标记；R9：跳变后窗口未满 3 个样本不判定
    r.addPoint(far(600), 5, T0 + 6000); // 攒窗口
    r.addPoint(far(600), 5, T0 + 7000); // 窗口满 3：第 1 次确认
    r.addPoint(far(600), 5, T0 + 8000); // 第 2 次确认 → 入库
    // points 只含 HOME 与 far(600)（真实快速移动/跳变超时后正常记录）
    expect(r.snapshot.points).toHaveLength(2);
  });

  it('非 WALKING 状态记点抛错', () => {
    const r = new RecorderState();
    expect(() => r.addPoint(HOME, 5, T0)).toThrow(/非法转移/);
  });
});

describe('静止过滤（R8 真机修复）', () => {
  it('R9 平滑窗口+门槛+确认：中位数候选连续超门槛才入库，入库点即中位数', () => {
    const r = started();
    r.addPoint(HOME, 5, T0); // 首点直入
    r.addPoint(far(3), 5, T0 + 1000); // 窗口 [3] 未满 3 → 攒样本不入库（R9：堵"初期原始点直入"漏洞）
    r.addPoint(far(4), 5, T0 + 2000); // 窗口 [3,4] 未满 3 → 不入库
    r.addPoint(far(6), 5, T0 + 3000); // 窗口 [3,4,6] 中位 far(4)：dist=4 < thr(5+1.5) → 不入库
    r.addPoint(far(9), 5, T0 + 4000); // 窗口 [3,4,6,9] 中位 far(4)：dist=4 < thr(5+2) → 不入库
    r.addPoint(far(12), 5, T0 + 5000); // 窗口 [3,4,6,9,12] 中位 far(6)：dist=6 < thr(5+2.5) → 不入库
    expect(r.snapshot.points).toHaveLength(1);
    r.addPoint(far(16), 5, T0 + 6000); // 中位 far(9)：dist=9 > thr(5+3)=8 → 第 1 次确认
    expect(r.snapshot.points).toHaveLength(1); // 连续确认未满 2 → 仍不入库
    r.addPoint(far(20), 5, T0 + 7000); // 中位 far(12)：dist=12 > thr(5+3.5) → 第 2 次确认 → 入库
    expect(r.snapshot.points).toHaveLength(2);
    expect(r.snapshot.points[1].pos).toEqual(far(12)); // 入库的是中位数（稳定估计）
    r.addPoint(far(24), 5, T0 + 8000); // 入库后窗口重置 [12]：未满 3 → 攒样本
    r.addPoint(far(28), 5, T0 + 9000); // [12,24,28] 中位 far(24)：dist=12 > thr(5+1) → 第 1 次确认
    r.addPoint(far(32), 5, T0 + 10000); // [12,24,28,32] 中位 far(24)：dist=12 > thr(5+1.5) → 第 2 次确认 → 入库
    expect(r.snapshot.points).toHaveLength(3);
    expect(r.snapshot.points[2].pos).toEqual(far(24));
  });

  it('坐着不动 2 分钟：抖动点全被过滤，轨迹不长线', () => {
    // 真机主诉复现：静止时 GPS 每 2s 抖动 ±4m——旧版全收，回放拉出多条线段的复杂轨迹
    const r = started();
    r.addPoint(HOME, 5, T0);
    let t = T0;
    for (let i = 1; i <= 60; i++) {
      t += 2000;
      r.addPoint(far(Math.abs(Math.sin(i)) * 4), 5, t);
    }
    expect(r.snapshot.points).toHaveLength(1);
  });

  it('R8 室内静止（±15m 振荡、精度 25m）：门槛随精度抬高，零入库', () => {
    // 真机主诉第二轮：坐 1~2 分钟仍拉出小段偏移——R7 固定 5m 门槛挡不住室内大抖动；
    // R8 门槛 = min(max(5, acc), 30) = 25m，±15m 振荡全滤
    const r = started();
    r.addPoint(HOME, 25, T0);
    let t = T0;
    for (let i = 1; i <= 60; i++) {
      t += 2000;
      r.addPoint(i % 2 === 0 ? far(15) : far(-15), 25, t);
    }
    expect(r.snapshot.points).toHaveLength(1);
  });

  it('R8 静止后真实走动（每 fix 3m）：轨迹正常记录不被误杀', () => {
    const r = started();
    r.addPoint(HOME, 5, T0);
    r.addPoint(far(3), 5, T0 + 1000); // 静止抖动
    r.addPoint(far(4), 5, T0 + 2000);
    let t = T0 + 2000;
    for (let m = 7; m <= 40; m += 3) {
      t += 2000;
      r.addPoint(far(m), 5, t); // 起步：每 2s 前进 3m
    }
    const pts = r.snapshot.points;
    expect(pts.length).toBeGreaterThanOrEqual(3);
    expect(haversineM(HOME, pts[pts.length - 1].pos)).toBeGreaterThan(25); // 末点已远离 Home
  });
});

describe('漂移根治（R9 真机第三轮：一阵一阵概率性漂移）', () => {
  it('R9 单向慢漂移（0.3m/s 持续 2 分钟）：漂移速度追不上门槛增速，零入库', () => {
    // R8 残留根因：中位数挡得住"振荡抖动"，挡不住"单向慢漂移"（多路径下单向游走，中位数跟着走）
    // R9 对策：门槛 = base + 静止秒数 × 0.5m/s——漂移 0.3m/s < 0.5m/s 恒追不上 → 永不长线
    const r = started();
    r.addPoint(HOME, 5, T0);
    for (let i = 1; i <= 120; i++) {
      r.addPoint(far(0.3 * i), 5, T0 + i * 1000);
    }
    expect(r.snapshot.points).toHaveLength(1);
  });

  it('R9 散布+偶发漂移阵（真机"一阵一阵"仿真）：入库点 ≤2，轨迹总长 <30m', () => {
    // 真机模型：90% 时间 ±8m 散布；10% 时间漂到 ±25m（"一阵一阵"），acc 虚标 5m
    const r = started();
    r.addPoint(HOME, 5, T0);
    let seed = 42;
    const rnd = () => {
      seed = (seed * 1664525 + 1013904223) % 4294967296;
      return seed / 4294967296;
    };
    let off = 0;
    for (let i = 1; i <= 120; i++) {
      const base = (rnd() - 0.5) * 16; // ±8m 散布
      const drift = rnd() < 0.1 ? (rnd() - 0.5) * 50 : 0; // 偶发 ±25m 漂移阵
      off = base + drift;
      r.addPoint(far(off), 5, T0 + i * 1000);
    }
    const pts = r.snapshot.points;
    expect(pts.length).toBeLessThanOrEqual(2); // 仅首点（至多再漏 1 个漂移点）
    let len = 0;
    for (let i = 1; i < pts.length; i++) len += haversineM(pts[i - 1].pos, pts[i].pos);
    expect(len).toBeLessThan(30); // 不再拉出长线
  });

  it('R9 窗口未满不入库：开始初期 2 秒内的原始抖动点直入漏洞已堵', () => {
    // R8 漏洞：首点后窗口只有 1 个样本时候选=原始点——抖动 20m 直接入库画线
    const r = started();
    r.addPoint(HOME, 5, T0);
    r.addPoint(far(20), 5, T0 + 1000); // 大幅抖动：窗口 [20] 未满 3 → 攒样本，不入库
    r.addPoint(far(20), 5, T0 + 2000); // 窗口 [20,20] 未满 3 → 不入库
    r.addPoint(far(2), 5, T0 + 3000); // 窗口 [20,20,2] 中位 far(20)：dist=20 > thr(5+1.5) → 第 1 次确认
    r.addPoint(far(2), 5, T0 + 4000); // 窗口 [20,20,2,2] 中位回落 far(2)：dist=2 < thr → 确认链断裂
    r.addPoint(far(2), 5, T0 + 5000); // 中位 far(2)：不足门槛 → 不入
    // 短暂 2 秒的 20m 抖动完全被挡——R8 旧版第 2 个点（原始候选）已直入库画线
    expect(r.snapshot.points).toHaveLength(1);
  });

  it('R9 短暂漂移阵（超门槛 1 秒即回落）：连续确认链断裂，不入库', () => {
    const r = started();
    r.addPoint(HOME, 5, T0);
    r.addPoint(far(2), 5, T0 + 1000); // 攒窗口
    r.addPoint(far(3), 5, T0 + 2000);
    r.addPoint(far(15), 5, T0 + 3000); // 窗口 [2,3,15] 中位 far(3)：dist=3 < thr → 不入
    r.addPoint(far(15), 5, T0 + 4000); // [2,3,15,15] 中位 far(9)：dist=9 > thr(5+2) → 确认 1
    r.addPoint(far(3), 5, T0 + 5000); // [2,3,15,15,3] 中位 far(3)：dist=3 < thr → 确认链断裂
    r.addPoint(far(3), 5, T0 + 6000); // 中位 far(3)：仍不足 → 不入
    expect(r.snapshot.points).toHaveLength(1); // "一阵"漂移被连续确认挡住
  });

  it('R9 真实步行（1.3m/s 持续 60 秒）：轨迹正常记录，末点已远离 Home', () => {
    const r = started();
    r.addPoint(HOME, 5, T0);
    for (let i = 1; i <= 60; i++) {
      r.addPoint(far(1.3 * i), 5, T0 + i * 1000);
    }
    const pts = r.snapshot.points;
    expect(pts.length).toBeGreaterThanOrEqual(4); // 轨迹形状保留
    expect(haversineM(HOME, pts[pts.length - 1].pos)).toBeGreaterThan(60); // 末点距 Home ≥60m
  });

  it('R9 createdAt 可显式指定：用时从点击「开始」起算（含等定位阶段）', () => {
    const t0 = 1_700_000_999_000;
    const r = new RecorderState({ createdAt: t0 });
    expect(r.snapshot.createdAt).toBe(t0);
    r.start(fixes(HOME), t0 + 30_000); // 等 30 秒定位后才 WALKING
    expect(r.snapshot.createdAt).toBe(t0); // 计时起点仍是点击时刻
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
