/** D22.1 计算 Worker：平滑/抽稀/TSP/morph 重采样全部移出主线程。
 * 消息协议：{id, kind:'analyze'|'morph', session, routes?} → {id, kind, ...结果} */

import { optimizeSession, scorecard, type Route } from './optimize.js';
import { buildPlan } from './playback.js';
import { projectToView } from './track.js';
import { resamplePolyline, routePolyline } from './polyline.js';
import type { SessionData } from './state.js';

const W = 480;
const H = 560;
const MORPH_POINTS = 180;

interface AnalyzeTask {
  id: number;
  kind: 'analyze';
  session: SessionData;
}

interface MorphTask {
  id: number;
  kind: 'morph';
  session: SessionData;
  routes: Route[];
}

type Task = AnalyzeTask | MorphTask;

const ctx = self as unknown as {
  onmessage: ((e: MessageEvent) => void) | null;
  postMessage: (msg: unknown) => void;
};

ctx.onmessage = (e: MessageEvent) => {
  const task = e.data as Task;
  try {
    if (task.kind === 'analyze') {
      const routes = optimizeSession(task.session);
      const card = scorecard(task.session, routes);
      const plan = buildPlan(task.session, W, H);
      ctx.postMessage({
        id: task.id,
        kind: 'analyze',
        routes,
        card,
        planPts: plan.pts.map((p) => ({ x: p.x, y: p.y })),
      });
    } else {
      const flyRoute = task.routes.find((r) => r.mode === 'fly')!;
      const flyLatLng = routePolyline(task.session, flyRoute.order);
      const flyXY = projectToView(flyLatLng, W, H);
      const plan = buildPlan(task.session, W, H);
      ctx.postMessage({
        id: task.id,
        kind: 'morph',
        actualRes: resamplePolyline(plan.pts.map((p) => ({ x: p.x, y: p.y })), MORPH_POINTS),
        flyRes: resamplePolyline(flyXY, MORPH_POINTS),
      });
    }
  } catch (err) {
    ctx.postMessage({ id: task.id, kind: 'error', message: String(err) });
  }
};
