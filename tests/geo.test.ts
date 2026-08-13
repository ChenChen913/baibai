import { describe, expect, it } from 'vitest';
import { haversineM, medianPos, nearest } from '../src/geo.js';

const R = 6371000;
const latDeg = (m: number) => (m / R) * (180 / Math.PI);
const lngDeg = (m: number, lat: number) =>
  (m / R) * (180 / Math.PI) / Math.cos((lat * Math.PI) / 180);

describe('haversineM', () => {
  it('同点距离为 0', () => {
    const p = { lat: 31.23, lng: 121.47 };
    expect(haversineM(p, p)).toBe(0);
  });

  it('纬度方向 100m 约为 100m', () => {
    const a = { lat: 31, lng: 121 };
    const b = { lat: 31 + latDeg(100), lng: 121 };
    expect(haversineM(a, b)).toBeCloseTo(100, 0);
  });

  it('经度方向 100m（31°N）约为 100m', () => {
    const a = { lat: 31, lng: 121 };
    const b = { lat: 31, lng: 121 + lngDeg(100, 31) };
    expect(haversineM(a, b)).toBeCloseTo(100, 0);
  });

  it('往返精度：10m 构造点回算误差 <1e-6', () => {
    const a = { lat: 31, lng: 121 };
    const p10 = { lat: 31 + latDeg(10), lng: 121 };
    expect(haversineM(a, p10)).toBeCloseTo(10, 6);
  });
});

describe('medianPos', () => {
  it('三点取中间点', () => {
    const p = medianPos([
      { lat: 31.2, lng: 121.4 },
      { lat: 31.0, lng: 121.0 },
      { lat: 31.1, lng: 121.2 },
    ]);
    expect(p).toEqual({ lat: 31.1, lng: 121.2 });
  });

  it('两点取排序后第一个（偶数取中下）', () => {
    const p = medianPos([
      { lat: 31.5, lng: 121.5 },
      { lat: 31.0, lng: 121.0 },
    ]);
    expect(p).toEqual({ lat: 31.0, lng: 121.0 });
  });

  it('空数组返回 null', () => {
    expect(medianPos([])).toBeNull();
  });
});

describe('nearest', () => {
  const home = { id: 'home', pos: { lat: 31, lng: 121 } };
  const n1 = { id: 'n1', pos: { lat: 31 + latDeg(120), lng: 121 } }; // 120m 北

  it('找到最近节点', () => {
    const r = nearest({ lat: 31 + latDeg(115), lng: 121 }, [home, n1]);
    expect(r.node!.id).toBe('n1');
    expect(r.distM).toBeLessThan(30);
  });

  it('空列表返回 null 与 Infinity', () => {
    const r = nearest({ lat: 0, lng: 0 }, []);
    expect(r.node).toBeNull();
    expect(r.distM).toBe(Infinity);
  });
});
