/** IndexedDB 持久化：活跃检查点 + 历史会话 + 全量导出（D2/D22） */

import type { Checkpoint, SessionData } from './state.js';

const DB_NAME = 'baibai';
const DB_VERSION = 1;
const STORE_ACTIVE = 'active';
const STORE_SESSIONS = 'sessions';
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

/** 全量导出（换手机备份，D2） */
export async function exportAllJson(): Promise<string> {
  const sessions = await listSessions();
  return JSON.stringify(
    { app: 'baibai', version: 1, exportedAt: new Date().toISOString(), sessions },
    null,
    2,
  );
}
