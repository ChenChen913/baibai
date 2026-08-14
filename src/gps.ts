/** GPS 定位封装：持续采样 + 最近 fix 环形缓冲 + 错误分类（供 UI 明确提示） */

import type { Fix } from './geo.js';

const BUFFER_MAX = 8;

export type GpsErrorKind = 'unsupported' | 'denied' | 'unavailable' | 'timeout';

/** 纯函数：geolocation 错误码 → 可读分类（可单测） */
export function describeGpsError(code: number): GpsErrorKind {
  switch (code) {
    case 1:
      return 'denied';
    case 2:
      return 'unavailable';
    case 3:
      return 'timeout';
    default:
      return 'unavailable';
  }
}

export interface GpsCallbacks {
  onFix(f: Fix): void;
  onError(kind: GpsErrorKind, message: string): void;
}

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

  start(cb: GpsCallbacks): void {
    if (!('geolocation' in navigator)) {
      cb.onError('unsupported', '此浏览器不支持定位');
      return;
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
        cb.onFix(f);
      },
      (err) => {
        cb.onError(describeGpsError(err.code), err.message);
      },
      // 去掉 maximumAge（允许缓存定位快速出点）；超时放宽到 30s（部分手机首定慢）
      { enableHighAccuracy: true, timeout: 30000 },
    );
  }

  stop(): void {
    if (this.watchId !== null) {
      navigator.geolocation.clearWatch(this.watchId);
    }
    this.watchId = null;
    // P19：停止时清空缓冲，避免恢复后 immediate pause 混入暂停前的旧点
    this.buffer = [];
  }
}
