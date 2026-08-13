/** 演示数据生成器：确定性构造一段"绕路的拜年"（回顾页/优化验收用） */

import type { LatLng } from './geo.js';
import { haversineM } from './geo.js';
import type { HouseNode, Mode, SessionData, TrackPoint, Visit } from './state.js';

const R = 6371000;
const SPEED_WALK_MS = 1.35;
const SPEED_BIKE_MS = 4.0;

function posAt(
  lat0: number,
  lng0: number,
  dN: number,
  dE: number,
): LatLng {
  return {
    lat: lat0 + ((dN / R) * 180) / Math.PI,
    lng: lng0 + ((dE / R) * 180) / Math.PI / Math.cos((lat0 * Math.PI) / 180),
  };
}

interface HouseSpec {
  id: string;
  name: string;
  n: number;
  e: number;
}

const HOUSES: HouseSpec[] = [
  { id: 'd1', name: '大伯家', n: 120, e: 60 },
  { id: 'd2', name: '二叔家', n: 260, e: 40 },
  { id: 'd3', name: '三舅家', n: 140, e: 300 },
  { id: 'd4', name: '四姨家', n: 320, e: 260 },
  { id: 'd5', name: '五伯家', n: 420, e: 140 },
  { id: 'd6', name: '六婶家', n: 60, e: 380 },
  { id: 'd7', name: '七哥家', n: 380, e: 400 },
  { id: 'd8', name: '小卖部', n: 240, e: 180 },
];

/** 实走顺序故意绕路：1→3→6→7→2→8→4→5 */
const VISIT_ORDER = ['d1', 'd3', 'd6', 'd7', 'd2', 'd8', 'd4', 'd5'];
/** 每段出行方式：第 4/5 段骑车 */
const SEG_MODES: Mode[] = ['walk', 'walk', 'walk', 'bike', 'bike', 'walk', 'walk', 'walk'];

export function generateDemoSession(): SessionData {
  const home: LatLng = { lat: 31.0, lng: 121.0 };
  const nodes: HouseNode[] = HOUSES.map((h, i) => ({
    id: h.id,
    name: h.name,
    autoNo: i + 1,
    pos: posAt(home.lat, home.lng, h.n, h.e),
  }));
  const byId = new Map(nodes.map((n) => [n.id, n.pos]));

  // 2026-02-17（丙午年春节）08:00 出发
  const t0 = new Date('2026-02-17T08:00:00+08:00').getTime();
  let t = t0;
  const points: TrackPoint[] = [];
  const visits: Visit[] = [];
  let seg = 0;

  const walkSegment = (
    a: LatLng,
    b: LatLng,
    speed: number,
    detour = 1.35,
  ): void => {
    const dist = haversineM(a, b) * detour;
    const durMs = (dist / speed) * 1000;
    const steps = Math.max(10, Math.ceil(dist / 20));
    for (let k = 1; k <= steps; k++) {
      const f = k / steps;
      const wig = Math.sin(f * Math.PI * 3) * 6; // 正弦弯曲
      t += durMs / steps;
      points.push({
        t,
        pos: {
          lat: a.lat + (b.lat - a.lat) * f + ((wig / R) * 180) / Math.PI,
          lng:
            a.lng +
            (b.lng - a.lng) * f +
            ((wig / R) * 180) / Math.PI / Math.cos((home.lat * Math.PI) / 180),
        },
        acc: 5,
        seg: `seg${seg}`,
      });
    }
  };

  let prev = home;
  for (let i = 0; i < VISIT_ORDER.length; i++) {
    const pos = byId.get(VISIT_ORDER[i])!;
    const mode = SEG_MODES[i];
    walkSegment(prev, pos, mode === 'bike' ? SPEED_BIKE_MS : SPEED_WALK_MS);
    const arriveT = t;
    t += (5 + (i % 3) * 5) * 60_000; // 停留 5~15 分钟
    visits.push({
      nodeId: VISIT_ORDER[i],
      arriveT,
      leaveT: t,
      mode,
    });
    prev = pos;
    seg += 1;
  }
  walkSegment(prev, home, SPEED_WALK_MS); // 回家

  // 一个跳变点（演示"剔除异常"）
  points.splice(
    Math.floor(points.length / 2),
    0,
    {
      t: points[Math.floor(points.length / 2)].t + 1,
      pos: posAt(home.lat, home.lng, 800, -500),
      acc: 5,
      seg: 'segX',
      jump: true,
    },
  );
  points.sort((a, b) => a.t - b.t);

  return {
    id: 'demo-2026',
    year: 2026,
    date: '2026-02-17',
    home,
    nodes,
    visits,
    points,
    state: 'FINISHED',
    currentMode: 'walk',
    finished: true,
    createdAt: t0,
    updatedAt: t,
  };
}
