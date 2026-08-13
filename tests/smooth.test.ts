import { describe, expect, it } from 'vitest';
import type { TrackPoint } from '../src/state.js';
import {
  douglasPeucker,
  jumpSplit,
  movingAverage,
  smoothTrack,
} from '../src/smooth.js';

const pt = (
  lat: number,
  lng: number,
  t: number,
  jump?: boolean,
): TrackPoint => ({ t, pos: { lat, lng }, acc: 5, seg: 's0', jump });

describe('jumpSplit', () => {
  it('无 jump 为单段', () => {
    const segs = jumpSplit([pt(0, 0, 0), pt(1, 1, 1), pt(2, 2, 2)]);
    expect(segs).toHaveLength(1);
    expect(segs[0]).toHaveLength(3);
  });

  it('中间 jump 切两段，jump 点归后段', () => {
    const segs = jumpSplit([
      pt(0, 0, 0),
      pt(1, 1, 1),
      pt(2, 2, 2, true),
      pt(3, 3, 3),
    ]);
    expect(segs).toHaveLength(2);
    expect(segs[0].map((p) => p.pos.lat)).toEqual([0, 1]);
    expect(segs[1].map((p) => p.pos.lat)).toEqual([2, 3]);
  });

  it('开头 jump 不产生空段', () => {
    const segs = jumpSplit([pt(0, 0, 0, true), pt(1, 1, 1)]);
    expect(segs).toHaveLength(1);
    expect(segs[0]).toHaveLength(2);
  });
});

describe('movingAverage', () => {
  it('长度不变、保留 t/acc/jump', () => {
    const pts = [pt(0, 0, 0), pt(1, 1, 1, true), pt(2, 2, 2)];
    const out = movingAverage(pts);
    expect(out).toHaveLength(3);
    expect(out[1].t).toBe(1);
    expect(out[1].jump).toBe(true);
  });

  it('共线等距点不变（窗口均值等于自身）', () => {
    const pts = [pt(0, 0, 0), pt(0.001, 0, 1), pt(0.002, 0, 2), pt(0.003, 0, 3), pt(0.004, 0, 4)];
    const out = movingAverage(pts);
    expect(out[2].pos.lat).toBeCloseTo(0.002, 9);
  });

  it('端点窗口收窄取均值', () => {
    const pts = [pt(0, 0, 0), pt(2, 0, 1), pt(4, 0, 2)];
    const out = movingAverage(pts);
    // w=5, half=2：首点窗口 [0,2] → lat 均值 2
    expect(out[0].pos.lat).toBeCloseTo(2, 9);
    expect(out[2].pos.lat).toBeCloseTo(2, 9);
  });
});

describe('smoothTrack', () => {
  it('长度不变', () => {
    const pts = [pt(0, 0, 0), pt(1, 1, 1, true), pt(2, 2, 2), pt(3, 3, 3)];
    expect(smoothTrack(pts)).toHaveLength(4);
  });

  it('窗口不跨 jump 段', () => {
    const farA = pt(0, 0, 0);
    const farB = pt(1, 1, 1, true); // 相距 ~157km 的跳变
    const farC = pt(2, 2, 2);
    const out = smoothTrack([farA, farB, farC]);
    // farB 在独立段，其坐标不受 farA/farC 拉拽
    expect(out[1].pos).toEqual({ lat: 1, lng: 1 });
    expect(out[0].pos).toEqual({ lat: 0, lng: 0 });
  });
});

describe('douglasPeucker', () => {
  it('直线仅保留两端点', () => {
    const line = [
      { lat: 0, lng: 0 },
      { lat: 1, lng: 1 },
      { lat: 2, lng: 2 },
      { lat: 3, lng: 3 },
    ];
    const out = douglasPeucker(line, 2);
    expect(out).toEqual([
      { lat: 0, lng: 0 },
      { lat: 3, lng: 3 },
    ]);
  });

  it('eps=0 全保留', () => {
    const line = [
      { lat: 0, lng: 0 },
      { lat: 1, lng: 1.5 },
      { lat: 2, lng: 2 },
    ];
    expect(douglasPeucker(line, 0)).toHaveLength(3);
  });

  it('直角拐点保留（拐点距离大于 eps）', () => {
    const corner = [
      { lat: 0, lng: 0 },
      { lat: 1, lng: 0 }, // 拐点
      { lat: 1, lng: 1 },
    ];
    expect(douglasPeucker(corner, 1)).toEqual(corner);
  });

  it('空与两点直通', () => {
    expect(douglasPeucker([], 2)).toEqual([]);
    const two = [
      { lat: 0, lng: 0 },
      { lat: 1, lng: 1 },
    ];
    expect(douglasPeucker(two, 2)).toEqual(two);
  });
});
