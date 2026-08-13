/** GPS 定位封装：持续采样 + 最近 fix 环形缓冲（供 start/pause/finish 取中位数） */

import type { Fix } from './geo.js';

const BUFFER_MAX = 8;

export class GpsTracker {
  private watchId: number | null = null;
  private buffer: Fix[] = [];
  private last: Fix | null = null;

  get active(): boolean {
    return this.watchId !== null;
  }

  get lastFix(): Fix | null {
    return this.last;
  }

  /** 最近 n 个 fix（n 默认 3，供中位数计算） */
  recent(n = 3): Fix[] {
    return this.buffer.slice(-n);
  }

  start(onFix: (f: Fix) => void): void {
    if (!('geolocation' in navigator)) {
      throw new Error('此浏览器不支持定位');
    }
    if (this.watchId !== null) return; // 已在采样
    this.watchId = navigator.geolocation.watchPosition(
      (p) => {
        const f: Fix = {
          pos: { lat: p.coords.latitude, lng: p.coords.longitude },
          acc: p.coords.accuracy,
        };
        this.last = f;
        this.buffer.push(f);
        if (this.buffer.length > BUFFER_MAX) this.buffer.shift();
        onFix(f);
      },
      (err) => console.warn('[gps]', err.message),
      { enableHighAccuracy: true, maximumAge: 1000, timeout: 15000 },
    );
  }

  stop(): void {
    if (this.watchId !== null) {
      navigator.geolocation.clearWatch(this.watchId);
    }
    this.watchId = null;
  }
}
