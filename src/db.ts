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

/** 全量导入（网页版/安卓版导出文件 → 本机，D2/契约 §1/§10）：
 * 校验 app/version（不符拒绝并提示升级）；忽略未知字段；sessions/plans 逐条校验后入库。
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
  for (const s of sessions as Array<Record<string, unknown>>) {
    const id = s?.id;
    if (typeof id !== 'string' || !SAFE_ID.test(id)) {
      throw new Error(`备份中存在不合法的会话 id：${String(id)}，已拒绝导入`);
    }
    await saveSession(s as unknown as SessionData);
  }
  const plans = obj?.plans;
  if (plans !== undefined && !Array.isArray(plans)) {
    throw new Error('备份中 plans 字段格式不正确');
  }
  if (Array.isArray(plans)) {
    for (const p of plans as Plan[]) {
      await savePlan(p);
    }
  }
  return sessions.length;
}
