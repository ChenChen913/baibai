/** IndexedDB 持久化：活跃检查点 + 历史会话 + 今年清单 + 全量导出（D2/D22/M4） */

import type { Checkpoint, SessionData } from './state.js';
import type { Plan } from './plan.js';

const DB_NAME = 'baibai';
const DB_VERSION = 2;
const STORE_ACTIVE = 'active';
const STORE_SESSIONS = 'sessions';
const STORE_PLANS = 'plans';
const ACTIVE_KEY = 'current';

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE_ACTIVE)) {
        db.createObjectStore(STORE_ACTIVE);
      }
      if (!db.objectStoreNames.contains(STORE_SESSIONS)) {
        db.createObjectStore(STORE_SESSIONS, { keyPath: 'id' });
      }
      if (!db.objectStoreNames.contains(STORE_PLANS)) {
        db.createObjectStore(STORE_PLANS, { keyPath: 'year' });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error ?? new Error('indexedDB open failed'));
  });
}

function store(
  db: IDBDatabase,
  name: string,
  mode: IDBTransactionMode,
): IDBObjectStore {
  return db.transaction(name, mode).objectStore(name);
}

function reqToPromise<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error ?? new Error('idb request failed'));
  });
}

/** 保存活跃会话检查点（整体覆盖写） */
export async function saveActive(ck: Checkpoint): Promise<void> {
  const db = await openDb();
  try {
    await reqToPromise(store(db, STORE_ACTIVE, 'readwrite').put(ck, ACTIVE_KEY));
  } finally {
    db.close();
  }
}

export async function loadActive(): Promise<Checkpoint | undefined> {
  const db = await openDb();
  try {
    return await reqToPromise(store(db, STORE_ACTIVE, 'readonly').get(ACTIVE_KEY));
  } finally {
    db.close();
  }
}

export async function clearActive(): Promise<void> {
  const db = await openDb();
  try {
    await reqToPromise(store(db, STORE_ACTIVE, 'readwrite').delete(ACTIVE_KEY));
  } finally {
    db.close();
  }
}

/** 结束后的历史会话 */
export async function saveSession(s: SessionData): Promise<void> {
  const db = await openDb();
  try {
    await reqToPromise(store(db, STORE_SESSIONS, 'readwrite').put(s));
  } finally {
    db.close();
  }
}

export async function listSessions(): Promise<SessionData[]> {
  const db = await openDb();
  try {
    return await reqToPromise(store(db, STORE_SESSIONS, 'readonly').getAll());
  } finally {
    db.close();
  }
}

export async function clearSessions(): Promise<void> {
  const db = await openDb();
  try {
    await reqToPromise(store(db, STORE_SESSIONS, 'readwrite').clear());
  } finally {
    db.close();
  }
}

/** 今年清单（M4） */
export async function savePlan(p: Plan): Promise<void> {
  const db = await openDb();
  try {
    await reqToPromise(store(db, STORE_PLANS, 'readwrite').put(p));
  } finally {
    db.close();
  }
}

export async function loadPlan(year: number): Promise<Plan | undefined> {
  const db = await openDb();
  try {
    return await reqToPromise(store(db, STORE_PLANS, 'readonly').get(year));
  } finally {
    db.close();
  }
}

export async function clearPlan(year: number): Promise<void> {
  const db = await openDb();
  try {
    await reqToPromise(store(db, STORE_PLANS, 'readwrite').delete(year));
  } finally {
    db.close();
  }
}

export async function listPlans(): Promise<Plan[]> {
  const db = await openDb();
  try {
    return await reqToPromise(store(db, STORE_PLANS, 'readonly').getAll());
  } finally {
    db.close();
  }
}

/** 全量导出（换手机备份，D2）：sessions + plans（清单，M4） */
export async function exportAllJson(): Promise<string> {
  const sessions = await listSessions();
  const plans = await listPlans();
  return JSON.stringify(
    { app: 'baibai', version: 1, exportedAt: new Date().toISOString(), sessions, plans },
    null,
    2,
  );
}

const SAFE_ID = /^[A-Za-z0-9_-]+$/;

type Rec = Record<string, unknown>;

const isPos = (v: unknown): boolean =>
  typeof v === 'object' &&
  v !== null &&
  typeof (v as Rec).lat === 'number' &&
  typeof (v as Rec).lng === 'number';

/**
 * E-9：导入的字段级校验。
 * 修复前只校验 app/version/id——一份 id 合法但缺 nodes/points 的 JSON 会被整体写库，
 * 之后历史列表统计、回顾页、三线页会在渲染时抛 TypeError（实测 analyzeSync 抛
 * "Cannot read properties of undefined (reading 'map')"）。
 * 这里做"结构完整性"校验（不求逐字段类型完美），拒绝明显不完整的条目并给出可读原因。
 */
function assertSessionShape(s: Rec): void {
  const bad = (why: string): never => {
    throw new Error(`备份中的会话数据不完整（${why}），已拒绝导入`);
  };
  if (!Array.isArray(s.nodes)) bad('nodes 不是数组');
  if (!Array.isArray(s.visits)) bad('visits 不是数组');
  if (!Array.isArray(s.points)) bad('points 不是数组');
  if (!isPos(s.home)) bad('home 坐标缺失');
  if (typeof s.date !== 'string') bad('date 缺失');
  if (typeof s.year !== 'number') bad('year 缺失');
  if (typeof s.createdAt !== 'number') bad('createdAt 缺失');
  if (typeof s.updatedAt !== 'number') bad('updatedAt 缺失');
  if (typeof s.state !== 'string') bad('state 缺失');
  if (typeof s.currentMode !== 'string') bad('currentMode 缺失');
  if (typeof s.finished !== 'boolean') bad('finished 缺失');
  for (const n of s.nodes as Rec[]) {
    if (typeof n?.id !== 'string' || typeof n?.autoNo !== 'number' || !isPos(n?.pos)) {
      bad('节点字段缺失');
    }
  }
  for (const p of s.points as Rec[]) {
    if (typeof p?.t !== 'number' || !isPos(p?.pos)) bad('轨迹点字段缺失');
  }
  for (const v of s.visits as Rec[]) {
    if (typeof v?.nodeId !== 'string' || typeof v?.arriveT !== 'number') {
      bad('到访记录字段缺失');
    }
  }
}

function assertPlanShape(p: unknown): void {
  const r = p as Rec | null;
  if (!r || typeof r.year !== 'number' || !Array.isArray(r.items)) {
    throw new Error('备份中的清单格式不正确，已拒绝导入');
  }
  for (const it of r.items as Rec[]) {
    if (typeof it?.name !== 'string') {
      throw new Error('备份中的清单条目缺少名字，已拒绝导入');
    }
    if (it.pos !== null && it.pos !== undefined && !isPos(it.pos)) {
      throw new Error('备份中的清单条目坐标不合法，已拒绝导入');
    }
  }
}

/** 全量导入（网页版/安卓版导出文件 → 本机，D2/契约 §1/§10）：
 * 校验 app/version（不符拒绝并提示升级）；忽略未知字段。
 * 先全量校验、再单事务写入：任一条不合法则一条都不落库（修复前逐条 saveSession，
 * 第 N 条校验失败时前 N-1 条已经写进去了，与"不合规文件整体拒绝"的口径不一致）。
 * 返回导入的会话数。 */
export async function importAllJson(text: string): Promise<number> {
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    throw new Error('文件不是有效的 JSON，请确认选择的是拜拜备份文件');
  }
  const obj = raw as { app?: unknown; version?: unknown; sessions?: unknown; plans?: unknown };
  if (obj?.app !== 'baibai') {
    throw new Error('这不是拜拜的备份文件，无法导入');
  }
  if (obj?.version !== 1) {
    throw new Error('备份版本不兼容，请先升级应用后再导入');
  }
  const sessions = obj?.sessions;
  if (!Array.isArray(sessions)) {
    throw new Error('备份内容不完整（缺少 sessions 字段）');
  }
  const plans = obj?.plans;
  if (plans !== undefined && !Array.isArray(plans)) {
    throw new Error('备份中 plans 字段格式不正确');
  }

  // 阶段一：只校验，不碰数据库
  const sessionRows: SessionData[] = [];
  for (const s of sessions as Array<Record<string, unknown>>) {
    const id = s?.id;
    if (typeof id !== 'string' || !SAFE_ID.test(id)) {
      throw new Error(`备份中存在不合法的会话 id：${String(id)}，已拒绝导入`);
    }
    assertSessionShape(s); // E-9：字段级校验（修复前只校验 id）
    sessionRows.push(s as unknown as SessionData);
  }
  const planRows: Plan[] = [];
  for (const p of (plans ?? []) as Plan[]) {
    assertPlanShape(p); // E-9：清单同样校验
    planRows.push(p);
  }

  // 阶段二：一个事务写两个 store——要么全进，要么全不进
  const db = await openDb();
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction([STORE_SESSIONS, STORE_PLANS], 'readwrite');
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error ?? new Error('导入写入失败'));
      tx.onabort = () => reject(tx.error ?? new Error('导入已回滚'));
      try {
        const sessionStore = tx.objectStore(STORE_SESSIONS);
        for (const s of sessionRows) sessionStore.put(s);
        const planStore = tx.objectStore(STORE_PLANS);
        for (const p of planRows) planStore.put(p);
      } catch (e) {
        tx.abort();
        reject(e);
      }
    });
  } finally {
    db.close();
  }
  return sessionRows.length;
}
