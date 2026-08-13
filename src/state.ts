/** 会话状态机（M1 记录闭环核心，纯逻辑、无 DOM/IO） */

import { Fix, LatLng, haversineM, medianPos, nearest } from './geo.js';

export type Mode = 'walk' | 'bike';
export type SessionState = 'IDLE' | 'WALKING' | 'PAUSED' | 'FINISHED';

export interface TrackPoint {
  t: number; // 毫秒时间戳
  pos: LatLng;
  acc: number; // 精度（米）
  seg: string; // 所属段 id
  jump?: boolean; // 疑似跳变点
}

export interface Visit {
  nodeId: string; // 'home' 或节点 id
  arriveT: number;
  leaveT: number | null;
  mode: Mode;
}

export interface HouseNode {
  id: string;
  name: string; // 名称（M2 回顾页可改）
  autoNo: number; // 自动编号（按拜访顺序，不含 Home）
  pos: LatLng;
  lowAcc?: boolean; // 坐标来自低精度 fix
}

export interface SessionData {
  id: string;
  year: number;
  date: string; // YYYY-MM-DD
  home: LatLng;
  nodes: HouseNode[]; // 不含 Home
  visits: Visit[];
  points: TrackPoint[];
  state: SessionState;
  currentMode: Mode;
  finished: boolean;
  createdAt: number;
  updatedAt: number;
}

export const HOME_ID = 'home';
export const MERGE_THRESHOLD_M = 10; // D10：≤10m 合并为同一节点
export const GOOD_ACC_M = 50; // ≤50m 精度的 fix 才参与中位数
export const JUMP_DIST_M = 100; // D22：跳变防护阈值
export const JUMP_DT_MS = 2000;

export type Action =
  | { type: 'start' }
  | { type: 'pause'; nodeId: string; created: boolean }
  | { type: 'resume' }
  | { type: 'finish'; prev: SessionState };

/** 检查点：会话 + 撤销历史 + 段计数（D22 完整恢复） */
export interface Checkpoint {
  session: SessionData;
  actions: Action[];
  segCounter: number;
}

let seq = 0;
function newId(prefix: string): string {
  seq += 1;
  return `${prefix}_${Date.now().toString(36)}_${seq.toString(36)}`;
}

export class RecorderState {
  private s: SessionData;
  private actions: Action[] = [];
  private segCounter = 0;

  constructor(init: Partial<SessionData> = {}) {
    this.s = {
      id: init.id ?? newId('s'),
      year: init.year ?? new Date().getFullYear(),
      date: init.date ?? new Date().toLocaleDateString('sv-SE'),
      home: init.home ?? { lat: 0, lng: 0 },
      nodes: init.nodes ?? [],
      visits: init.visits ?? [],
      points: init.points ?? [],
      state: init.state ?? 'IDLE',
      currentMode: init.currentMode ?? 'walk',
      finished: init.finished ?? false,
      createdAt: init.createdAt ?? Date.now(),
      updatedAt: init.updatedAt ?? Date.now(),
    };
    if (init.state === 'FINISHED') this.s.finished = true;
  }

  /** 当前会话快照（深拷贝，可安全序列化） */
  get snapshot(): SessionData {
    return structuredClone(this.s);
  }

  /** 检查点：会话 + 撤销历史 + 段计数（D22：崩溃后完整恢复，含撤销能力） */
  checkpoint(): Checkpoint {
    return {
      session: this.snapshot,
      actions: structuredClone(this.actions),
      segCounter: this.segCounter,
    };
  }

  static restore(ck: Checkpoint): RecorderState {
    const r = new RecorderState(ck.session);
    r.actions = structuredClone(ck.actions ?? []);
    r.segCounter = ck.segCounter ?? 0;
    return r;
  }

  get state(): SessionState {
    return this.s.state;
  }

  /** IDLE → WALKING：确定 Home（中位数，允许 fallback） */
  start(homeFixes: Fix[], now: number, fallback?: Fix): void {
    if (this.s.state !== 'IDLE') throw new Error('非法转移：仅待机状态可开始');
    const good = homeFixes.filter((f) => f.acc <= GOOD_ACC_M);
    const base = good.length > 0 ? good : homeFixes;
    let home = medianPos(base.map((f) => f.pos));
    if (!home && fallback) home = fallback.pos;
    if (!home) throw new Error('无有效定位，无法确定 Home');
    this.s.home = home;
    this.s.state = 'WALKING';
    this.s.finished = false;
    this.actions.push({ type: 'start' });
    this.touch(now);
  }

  /** WALKING → PAUSED：创建节点（≤10m 合并，D10）或合并到已有节点/Home；返回该户 */
  pause(fixes: Fix[], now: number): HouseNode {
    if (this.s.state !== 'WALKING') throw new Error('非法转移：仅移动中可暂停');
    const good = fixes.filter((f) => f.acc <= GOOD_ACC_M);
    let lowAcc = false;
    let pos = medianPos(good.map((f) => f.pos));
    if (!pos && fixes.length > 0) {
      pos = fixes[fixes.length - 1].pos; // 全部低精度：用原始最后一点
      lowAcc = true;
    }
    if (!pos) throw new Error('无有效定位，无法确定节点位置');

    const { node: n, distM } = nearest(pos, this.allLocated());
    let node: HouseNode;
    let nodeId: string;
    let created = false;
    if (n && distM <= MERGE_THRESHOLD_M) {
      nodeId = n.id;
      node =
        n.id === HOME_ID
          ? { id: HOME_ID, name: 'Home', autoNo: 0, pos: this.s.home }
          : this.s.nodes.find((x) => x.id === n.id)!;
    } else {
      nodeId = newId('n');
      node = {
        id: nodeId,
        name: '',
        autoNo: this.s.nodes.length + 1,
        pos,
        ...(lowAcc ? { lowAcc: true } : {}),
      };
      this.s.nodes.push(node);
      created = true;
    }

    this.s.visits.push({
      nodeId,
      arriveT: now,
      leaveT: null,
      mode: this.s.currentMode,
    });
    this.s.state = 'PAUSED';
    this.actions.push({ type: 'pause', nodeId, created });
    this.touch(now);
    return node;
  }

  /** PAUSED → WALKING：离开该户，段 id 自增 */
  resume(now: number): void {
    if (this.s.state !== 'PAUSED') throw new Error('非法转移：仅在某户可继续');
    const v = this.s.visits[this.s.visits.length - 1];
    v.leaveT = now;
    this.segCounter += 1;
    this.s.state = 'WALKING';
    this.actions.push({ type: 'resume' });
    this.touch(now);
  }

  /**
   * → FINISHED：到家结束。
   * SPEC §3 修正：允许在 WALKING 或 PAUSED 结束（到家直接按结束，不必先暂停）。
   * 距 Home >10m 时需 force=true（UI 弹确认）。
   */
  finish(
    fixes: Fix[],
    now: number,
    force = false,
  ): { ok: true } | { ok: false; distM: number } {
    if (this.s.state !== 'WALKING' && this.s.state !== 'PAUSED') {
      throw new Error('非法转移：仅移动中或某户可结束');
    }
    const good = fixes.filter((f) => f.acc <= GOOD_ACC_M);
    const pos =
      medianPos(good.map((f) => f.pos)) ??
      (fixes.length > 0 ? fixes[fixes.length - 1].pos : null);
    const distM = pos ? haversineM(pos, this.s.home) : Infinity;
    if (pos && distM <= MERGE_THRESHOLD_M) {
      this.finalize(now);
      return { ok: true };
    }
    if (force) {
      this.finalize(now);
      return { ok: true };
    }
    return { ok: false, distM };
  }

  private finalize(now: number): void {
    const prev = this.s.state;
    this.s.state = 'FINISHED';
    this.s.finished = true;
    this.actions.push({ type: 'finish', prev });
    this.touch(now);
  }

  /** 记录轨迹点（仅 WALKING）；跳变防护（D22 最小版） */
  addPoint(pos: LatLng, acc: number, now: number): TrackPoint {
    if (this.s.state !== 'WALKING') {
      throw new Error('非法转移：仅移动中记录轨迹点');
    }
    const p: TrackPoint = { t: now, pos, acc, seg: `seg${this.segCounter}` };
    const prev = this.s.points[this.s.points.length - 1];
    if (
      prev &&
      now - prev.t < JUMP_DT_MS &&
      haversineM(prev.pos, pos) > JUMP_DIST_M
    ) {
      p.jump = true;
    }
    this.s.points.push(p);
    return p;
  }

  /** 出行方式切换（D19：默认走路，骑车段出发前点一下，到下一户自动回走路） */
  setMode(mode: Mode, now: number): void {
    if (this.s.state === 'FINISHED') return;
    this.s.currentMode = mode;
    this.touch(now);
  }

  /** 撤销最近一次操作（LIFO；D19 R2） */
  undo(): boolean {
    const a = this.actions.pop();
    if (!a) return false;
    switch (a.type) {
      case 'start':
        // 撤销开始：回到待机并清空本次数据
        this.s.nodes = [];
        this.s.visits = [];
        this.s.points = [];
        this.s.state = 'IDLE';
        this.segCounter = 0;
        break;
      case 'pause': {
        this.s.visits.pop();
        if (a.created) {
          const i = this.s.nodes.findIndex((x) => x.id === a.nodeId);
          if (i >= 0) this.s.nodes.splice(i, 1);
        }
        this.s.state = 'WALKING';
        break;
      }
      case 'resume': {
        const v = this.s.visits[this.s.visits.length - 1];
        if (v) v.leaveT = null;
        this.segCounter -= 1;
        this.s.state = 'PAUSED';
        break;
      }
      case 'finish':
        this.s.state = a.prev;
        this.s.finished = false;
        break;
    }
    return true;
  }

  /** 所有可合并目标：Home + 全部节点 */
  private allLocated(): { id: string; pos: LatLng }[] {
    return [
      { id: HOME_ID, pos: this.s.home },
      ...this.s.nodes.map((n) => ({ id: n.id, pos: n.pos })),
    ];
  }

  private touch(now: number): void {
    this.s.updatedAt = now;
  }
}
