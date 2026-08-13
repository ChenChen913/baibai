import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it } from 'vitest';
import { RecorderState } from '../src/state.js';
import {
  clearActive,
  clearSessions,
  exportAllJson,
  listSessions,
  loadActive,
  saveActive,
  saveSession,
} from '../src/db.js';

const T0 = 1_700_000_000_000;
const HOME = { lat: 31, lng: 121 };

function makeCheckpoint() {
  const r = new RecorderState();
  r.start([{ pos: HOME, acc: 5 }], T0);
  r.pause([{ pos: { lat: 31.001, lng: 121 }, acc: 5 }], T0 + 1000);
  return r.checkpoint();
}

describe('db', () => {
  beforeEach(async () => {
    await clearActive();
    await clearSessions();
  });

  it('活跃检查点保存/读取往返一致', async () => {
    const ck = makeCheckpoint();
    await saveActive(ck);
    expect(await loadActive()).toEqual(ck);
  });

  it('重复保存覆盖旧检查点', async () => {
    await saveActive(makeCheckpoint());
    const ck2 = makeCheckpoint();
    ck2.session.year = 2027;
    await saveActive(ck2);
    expect((await loadActive())!.session.year).toBe(2027);
  });

  it('clear 后读不到', async () => {
    await saveActive(makeCheckpoint());
    await clearActive();
    expect(await loadActive()).toBeUndefined();
  });

  it('历史会话保存/列表/导出', async () => {
    const r = new RecorderState();
    r.start([{ pos: HOME, acc: 5 }], T0);
    r.pause([{ pos: { lat: 31.001, lng: 121 }, acc: 5 }], T0 + 1000);
    r.resume(T0 + 2000);
    r.finish([{ pos: HOME, acc: 5 }], T0 + 3000);
    await saveSession(r.snapshot);

    const list = await listSessions();
    expect(list).toHaveLength(1);
    expect(list[0].finished).toBe(true);

    const parsed = JSON.parse(await exportAllJson()) as {
      app: string;
      sessions: unknown[];
    };
    expect(parsed.app).toBe('baibai');
    expect(parsed.sessions).toHaveLength(1);
  });
});
