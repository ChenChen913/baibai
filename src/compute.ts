/** 计算入口（D22.1）：优先 Worker 后台计算；无 Worker 环境（jsdom 测试等）同步回退。
 * 协议与 src/worker.ts 对应。 */

import { optimizeSession, scorecard, type Route } from './optimize.js';
import { buildPlan } from './playback.js';
import { projectToView } from './track.js';
import { resamplePolyline, routePolyline, type XY } from './polyline.js';
import type { SessionData } from './state.js';

const W = 480;
const H = 560;
const MORPH_POINTS = 180;

export type Card = ReturnType<typeof scorecard>;

export interface AnalyzeResult {
  routes: Route[];
  card: Card;
  planPts: XY[];
}

export interface MorphResult {
  actualRes: XY[];
  flyRes: XY[];
}

export function analyzeSync(s: SessionData): AnalyzeResult {
  const routes = optimizeSession(s);
  const card = scorecard(s, routes);
  const plan = buildPlan(s, W, H);
  return { routes, card, planPts: plan.pts.map((p) => ({ x: p.x, y: p.y })) };
}

export function morphSync(s: SessionData, routes: Route[]): MorphResult {
  const flyRoute = routes.find((r) => r.mode === 'fly')!;
  const flyLatLng = routePolyline(s, flyRoute.order);
  const flyXY = projectToView(flyLatLng, W, H);
  const plan = buildPlan(s, W, H);
  return {
    actualRes: resamplePolyline(plan.pts.map((p) => ({ x: p.x, y: p.y })), MORPH_POINTS),
    flyRes: resamplePolyline(flyXY, MORPH_POINTS),
  };
}

let worker: Worker | null = null;
let seq = 0;
const pending = new Map<number, { resolve: (msg: unknown) => void; reject: (e: Error) => void }>();

function ensureWorker(): Worker | null {
  if (typeof Worker === 'undefined') return null;
  if (worker) return worker;
  try {
    worker = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' });
    worker.onmessage = (e: MessageEvent) => {
      const msg = e.data as { id: number; kind: string; message?: string };
      const entry = pending.get(msg.id);
      if (!entry) return;
      pending.delete(msg.id);
      if (msg.kind === 'error') {
        entry.reject(new Error(msg.message ?? '计算失败'));
      } else {
        entry.resolve(msg);
      }
    };
    worker.onerror = (e) => {
      console.warn('[worker]', e.message);
      for (const [id, entry] of pending) {
        entry.reject(new Error('计算线程异常，已回退'));
        pending.delete(id);
      }
      worker = null;
    };
  } catch {
    worker = null;
  }
  return worker;
}

function post<T>(msg: unknown): Promise<T> {
  const w = ensureWorker();
  if (!w) return Promise.reject(new Error('no-worker'));
  return new Promise<T>((resolve, reject) => {
    const id = ++seq;
    pending.set(id, { resolve: resolve as (m: unknown) => void, reject });
    w.postMessage({ ...(msg as object), id });
  });
}

export function analyze(s: SessionData): Promise<AnalyzeResult> {
  if (typeof Worker === 'undefined') return Promise.resolve(analyzeSync(s));
  return post<AnalyzeResult>({ kind: 'analyze', session: s });
}

export function morph(s: SessionData, routes: Route[]): Promise<MorphResult> {
  if (typeof Worker === 'undefined') return Promise.resolve(morphSync(s, routes));
  return post<MorphResult>({ kind: 'morph', session: s, routes });
}
