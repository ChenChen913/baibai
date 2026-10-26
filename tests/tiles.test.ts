import { describe, expect, it } from 'vitest';
import {
  PRELOAD_MAX_TILES,
  normalizeTileUrl,
  preloadTileList,
  preloadTileListFor,
  tileUrl,
  tileX,
  tileY,
} from '../src/tiles.js';

describe('瓦片数学（与安卓 TileMathTest 同值契约）', () => {
  it('瓦片号已知值', () => {
    // z1 全球 2x2
    expect(tileX(-180, 1)).toBe(0);
    expect(tileX(179.9, 1)).toBe(1);
    expect(tileY(85, 1)).toBe(0);
    expect(tileY(-85, 1)).toBe(1);
    // 潍坊附近 z13 确定性
    const x = tileX(119.1, 13);
    const y = tileY(36.71, 13);
    expect(x).toBeGreaterThanOrEqual(0);
    expect(x).toBeLessThanOrEqual(8191);
    expect(y).toBeGreaterThanOrEqual(0);
    expect(y).toBeLessThanOrEqual(8191);
    expect(tileX(119.1, 13)).toBe(x);
    expect(tileY(36.71, 13)).toBe(y);
  });

  it('预载清单数量合理且含两种样式', () => {
    const list = preloadTileList(36.71, 119.1);
    expect(list.length).toBeGreaterThanOrEqual(100);
    expect(list.length).toBeLessThanOrEqual(300);
    expect(list.some((u) => u.includes('style=6'))).toBe(true);
    expect(list.some((u) => u.includes('style=8'))).toBe(true);
    expect(new Set(list).size).toBe(list.length); // 无重复
  });

  // 旧版只按 Home ±0.02° 预载：清单上的户在 2.2km 外时，离线去到那里会没图
  it('多点预载覆盖所有已知点位（z16 瓦片齐全）', () => {
    const pts = [
      { lat: 36.71, lng: 119.1 },
      { lat: 36.725, lng: 119.12 },
      { lat: 36.7, lng: 119.08 },
    ];
    const list = preloadTileListFor(pts);
    for (const p of pts) {
      const url = `x=${tileX(p.lng, 16)}&y=${tileY(p.lat, 16)}&z=16`;
      expect(list.some((u) => u.includes(url))).toBe(true);
    }
  });

  it('预载上限：极端分散点位不超上限且仍有瓦片', () => {
    const list = preloadTileListFor([
      { lat: 0, lng: 0 },
      { lat: 10, lng: 10 },
    ]);
    expect(list.length).toBeGreaterThan(0);
    expect(list.length).toBeLessThanOrEqual(PRELOAD_MAX_TILES);
    expect(list.some((u) => u.includes('z=13'))).toBe(true);
  });

  it('空点位返回空清单', () => {
    expect(preloadTileListFor([])).toEqual([]);
  });

  it('normalizeTileUrl 去子域数字', () => {
    const a = normalizeTileUrl('https://webst03.is.autonavi.com/appmaptile?style=6&x=1&y=2&z=13');
    const b = normalizeTileUrl('https://webst01.is.autonavi.com/appmaptile?style=6&x=1&y=2&z=13');
    expect(a).toBe('https://webst0*.is.autonavi.com/appmaptile?style=6&x=1&y=2&z=13');
    expect(a).toBe(b);
  });

  it('越界坐标收敛到合法瓦片号', () => {
    expect(tileX(180, 13)).toBe(8191);
    expect(tileY(89.9, 13)).toBe(0); // 最北
    expect(tileY(-89.9, 17)).toBe(131071); // 最南 = (1<<17)-1
    expect(tileUrl('sat', 1, 2, 13).includes('style=6')).toBe(true);
  });
});
