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
export const FINISH_OK_M = 20; // 结束拜年自动判定半径：GPS 民码误差 ±3~10m，到家门口不烦用户
export const GOOD_ACC_M = 50; // ≤50m 精度的 fix 才参与中位数
export const JUMP_DIST_M = 100; // D22：跳变防护阈值
export const JUMP_DT_MS = 2000;
export const MIN_MOVE_M = 5; // R7/R8：静止位移门槛基线——GPS 报多少米精度，就至少走够多少米才入库
export const MOVE_THR_MAX_M = 30; // R8：精度自适应门槛上限——acc 再差也保证真实走动每 ~30m 留一个点
export const SMOOTH_WINDOW = 5; // R8：中位数平滑窗口大小——吸收振荡抖动与单点坏值
// R9：静止越久门槛越高（真机第三轮：漂移"一阵一阵"——中位数挡不住单向慢漂，门槛随静止时长抬升压制）
export const IDLE_GROW_M_PER_S = 0.5; // R9：静止门槛增速——0.5m/s（人从静止起步的合理加速度以下）
export const MOVE_THR_IDLE_CAP_M = 50; // R9：静止门槛封顶——再久也不超过 50m（保证真实走动最迟 50m 必留点）
export const MOVE_CONFIRM_N = 2; // R9：连续确认——连续 2 个平滑候选都超门槛才入库（挡"一阵"短暂漂移）

export type Action =
  | { type: 'start' }
  | { type: 'pause'; nodeId: string; created: boolean; mode: Mode }
  | { type: 'resume' }
  | { type: 'finish'; prev: SessionState };

/** 检查点：会话 + 撤销历史 + 段计数（D22 完整恢复） */
export interface Checkpoint {
  session: SessionData;
  actions: Action[];
  segCounter: number;
}

let seq = 0;
export function newId(prefix: string): string {
  seq += 1;
  return `${prefix}_${Date.now().toString(36)}_${seq.toString(36)}`;
}

export class RecorderState {
  private s: SessionData;
  private actions: Action[] = [];
  private segCounter = 0;
  // R8：中位数平滑窗口（运行时态，不入快照/检查点——崩溃恢复后从空窗重启，无碍）
  private smoothBuf: LatLng[] = [];
  // R9：连续确认计数（运行时态，不入快照/检查点——与 smoothBuf 一同从 0 重启）
  private confirmCount = 0;
  // R9.1：本段（start/resume 起）行走起点时刻——静止门槛的"静止时长"只算段内，
  // 不把在户内停留的几分钟算进去（否则出门短走访 <50m 永远追不上门槛，整段零入库丢边）
  private segStartT = 0;

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
    this.smoothBuf = []; // R8：新会话平滑窗口从空开始
    this.confirmCount = 0; // R9：确认链一并清零
    this.segStartT = now; // R9.1：段起点
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
    this.actions.push({
      type: 'pause',
      nodeId,
      created,
      mode: this.s.currentMode,
    });
    this.s.currentMode = 'walk'; // D19：到下一户自动回走路
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
    this.smoothBuf = []; // R8：新段平滑窗口从空开始（上一段的旧 fix 不拖慢本段起步）
    this.confirmCount = 0; // R9：确认链一并清零
    this.segStartT = now; // R9.1：新段起点——户内停留时长不计入静止门槛
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
    if (pos && distM <= FINISH_OK_M) {
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

  /**
   * 记录轨迹点（仅 WALKING）。
   * R8 三道入口闸门 + R9 三道补强（真机第三轮：漂移"一阵一阵"——概率性出现小偏移轨迹）。
   * R8 根因：中位数只能挡"振荡抖动"，挡不住"单向慢漂移"（多路径效应下单向游走，
   * 中位数跟着走）；且窗口未满 3 个样本时候选=原始抖动点；国产 ROM acc 虚标 5m 时门槛过低。
   *
   * ① 跳变丢弃（R7 原样）：距上一入库点 2s 内 >100m（人力不可达，必为 GPS 坏点）
   *    直接丢弃且不进平滑窗口（防污染中位数），返回值带 jump 标记；
   * ② 中位数平滑（R8 原样）：原始 fix 进入滑动窗口（SMOOTH_WINDOW 个），
   *    入库候选 = 窗口各分量中位数（样本 ≥3 才可信，不足用原始值）；
   * ③ 窗口未满不入库（R9 新增）：首点后窗口攒满 3 个样本前一律不入库——
   *    挡住"开始初期第 2 个原始抖动点直入"的漏洞（起步记录最多延迟 ~3 秒，无碍）；
   * ④ 静止门槛随段内静止时长抬升（R9 新增，R9.1 修正起算点）：
   *    thr = min(max(5m, acc), 30m) + 段内静止秒数 × 0.5m/s，封顶 50m——
   *    单向慢漂移（<0.5m/s）永远追不上门槛，彻底不长线；
   *    静止时长从 max(上一入库点, 段起点) 起算：段起点 = start/resume 时刻，
   *    在户内停留的几分钟不算静止——否则出门走访邻户（<50m）整段零入库、回顾页丢边；
   * ⑤ 连续确认（R9 新增）：连续 MOVE_CONFIRM_N 个平滑候选都超门槛才入库——
   *    "一阵"短暂漂移超门槛一两秒后回落，确认链断裂被挡；真走动持续超门槛，确认链成立；
   * ⑥ 入库后窗口重置 + 确认清零（R8 原样扩展）：新入库点成为下一轮平滑锚点。
   */
  addPoint(pos: LatLng, acc: number, now: number): TrackPoint {
    if (this.s.state !== 'WALKING') {
      throw new Error('非法转移：仅移动中记录轨迹点');
    }
    const seg = `seg${this.segCounter}`;
    const prev = this.s.points[this.s.points.length - 1];
    // ① 跳变点：不入库、不进平滑窗口
    if (
      prev &&
      now - prev.t < JUMP_DT_MS &&
      haversineM(prev.pos, pos) > JUMP_DIST_M
    ) {
      return { t: now, pos, acc, seg, jump: true };
    }
    // ② 原始 fix 进滑动窗口，取中位数为入库候选
    this.smoothBuf.push(pos);
    if (this.smoothBuf.length > SMOOTH_WINDOW) this.smoothBuf.shift();
    const cand = this.smoothBuf.length >= 3 ? medianPos(this.smoothBuf)! : pos;
    const p: TrackPoint = { t: now, pos: cand, acc, seg };
    if (prev) {
      // ③ 窗口未满 3：不判定（攒样本）——防开始初期原始抖动点直入
      if (this.smoothBuf.length < 3) return p;
      // ④ 静止门槛 = 精度自适应基线 + 段内静止时长增速，封顶 50m
      //    R9.1：静止时长从 max(上一入库点, 段起点) 起算——在户内停留的几分钟不算静止，
      //    否则出门后走访隔壁邻户（<50m）永远追不上门槛，整段零入库、回顾页丢边
      const base = Math.min(Math.max(MIN_MOVE_M, acc), MOVE_THR_MAX_M);
      const idleSec = (now - Math.max(prev.t, this.segStartT)) / 1000;
      const thr = Math.min(base + idleSec * IDLE_GROW_M_PER_S, MOVE_THR_IDLE_CAP_M);
      const dist = haversineM(prev.pos, cand);
      // ⑤ 连续确认：连续 MOVE_CONFIRM_N 个候选都超门槛才入库
      if (dist < thr) {
        this.confirmCount = 0; // 回落 → 确认链断裂
        return p;
      }
      this.confirmCount += 1;
      if (this.confirmCount < MOVE_CONFIRM_N) return p;
    }
    this.s.points.push(p);
    this.smoothBuf = [cand]; // ⑥ 入库后窗口重置
    this.confirmCount = 0;
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
        this.smoothBuf = []; // R8：平滑窗口一并清空
        this.confirmCount = 0; // R9：确认链一并清零
        this.segStartT = 0; // R9.1：段起点一并复位
        break;
      case 'pause': {
        this.s.visits.pop();
        if (a.created) {
          const i = this.s.nodes.findIndex((x) => x.id === a.nodeId);
          if (i >= 0) this.s.nodes.splice(i, 1);
        }
        this.s.currentMode = a.mode; // 还原暂停前的出行方式
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
