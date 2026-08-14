import { describe, expect, it } from 'vitest';
import { wgs2gcj } from '../src/wgs2gcj.js';

describe('wgs2gcj 火星坐标转换', () => {
  it('中国境内有偏移（北京天安门附近，偏移约数百米）', () => {
    const [lat, lng] = wgs2gcj(39.9087, 116.3975);
    expect(Math.abs(lat - 39.9087)).toBeGreaterThan(0.0005);
    expect(Math.abs(lng - 116.3975)).toBeGreaterThan(0.0005);
    // 经典值：偏移量在 0.001~0.01 度量级
    expect(Math.abs(lat - 39.9087)).toBeLessThan(0.01);
    expect(Math.abs(lng - 116.3975)).toBeLessThan(0.01);
  });
  it('山东昌乐附近偏移为正且有限', () => {
    const [lat, lng] = wgs2gcj(36.71, 119.1);
    expect(Number.isFinite(lat)).toBe(true);
    expect(Number.isFinite(lng)).toBe(true);
    expect(lat).toBeGreaterThan(36.71);
    expect(lng).toBeGreaterThan(119.1);
  });
  it('境外原样返回', () => {
    expect(wgs2gcj(35.68, 139.69)).toEqual([35.68, 139.69]); // 东京
  });
  it('边界外不转换', () => {
    expect(wgs2gcj(56.0, 120.0)).toEqual([56.0, 120.0]);
  });
});
