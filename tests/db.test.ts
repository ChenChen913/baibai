import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it } from 'vitest';
import { RecorderState } from '../src/state.js';
import {
  clearActive,
  clearSessions,
  exportAllJson,
  importAllJson,
  listSessions,
  loadActive,
  loadPlan,
  saveActive,
  savePlan,
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

  it('导出→导入往返一致（含 plans）', async () => {
    const r = new RecorderState();
    r.start([{ pos: HOME, acc: 5 }], T0);
    r.pause([{ pos: { lat: 31.001, lng: 121 }, acc: 5 }], T0 + 1000);
    r.resume(T0 + 2000);
    r.finish([{ pos: HOME, acc: 5 }], T0 + 3000);
    await saveSession(r.snapshot);
    await savePlan({ year: 2026, createdAt: 0, updatedAt: 0, items: [{ name: '大伯家', pos: null }] });
    const json = await exportAllJson();

    await clearSessions();
    const n = await importAllJson(json);
    expect(n).toBe(1);
    expect((await listSessions())[0].id).toBe(r.snapshot.id);
    expect((await loadPlan(2026))?.items[0].name).toBe('大伯家');
  });

  it('导入拒绝：app 不是 baibai', async () => {
    await expect(
      importAllJson(JSON.stringify({ app: 'other', version: 1, sessions: [] })),
    ).rejects.toThrow(/不是拜拜/);
  });

  it('导入拒绝：version 不符', async () => {
    await expect(
      importAllJson(JSON.stringify({ app: 'baibai', version: 2, sessions: [] })),
    ).rejects.toThrow(/版本不兼容/);
  });

  it('导入拒绝：非法 JSON 与路径穿越 id', async () => {
    await expect(importAllJson('not json')).rejects.toThrow(/JSON/);
    await expect(
      importAllJson(
        JSON.stringify({ app: 'baibai', version: 1, sessions: [{ id: '../checkpoint', year: 2026 }] }),
      ),
    ).rejects.toThrow(/id/);
  });

  // E-9：字段级校验——修复前只校验 app/version/id，一份 id 合法但缺 nodes/points 的 JSON
  // 会被整体写库，之后历史统计与回顾页渲染时抛 TypeError（实测 analyzeSync 直接崩）
  it('导入拒绝：会话缺 nodes/points 等关键字段', async () => {
    const base = { id: 'ok_1', year: 2026, date: '2026-02-17' };
    const bad = [
      { ...base }, // 缺一切数组与 home
      { ...base, nodes: [], visits: [], points: [] }, // 缺 home
      {
        ...base,
        nodes: undefined,
        visits: [],
        points: [],
        home: { lat: 31, lng: 121 },
        createdAt: 0,
        updatedAt: 0,
        state: 'FINISHED',
        currentMode: 'walk',
        finished: true,
      }, // nodes 不是数组
    ];
    for (const s of bad) {
      await expect(
        importAllJson(JSON.stringify({ app: 'baibai', version: 1, sessions: [s] })),
      ).rejects.toThrow(/不完整/);
    }
    // 结构完整的会话仍可正常导入
    const good = {
      ...base,
      nodes: [],
      visits: [],
      points: [],
      home: { lat: 31, lng: 121 },
      createdAt: 0,
      updatedAt: 0,
      state: 'FINISHED',
      currentMode: 'walk',
      finished: true,
    };
    await expect(
      importAllJson(JSON.stringify({ app: 'baibai', version: 1, sessions: [good] })),
    ).resolves.toBe(1);
  });

  // 原子导入：修复前逐条 saveSession，第 N 条失败时前 N-1 条已经落库
  it('导入失败不落库：后续条目非法时前面的也不写入', async () => {
    const valid = {
      id: 'ok_1',
      year: 2026,
      date: '2026-02-17',
      nodes: [],
      visits: [],
      points: [],
      home: { lat: 31, lng: 121 },
      createdAt: 0,
      updatedAt: 0,
      state: 'FINISHED',
      currentMode: 'walk',
      finished: true,
    };
    const invalid = { ...valid, id: 'ok_2', nodes: 'not-an-array' };
    await expect(
      importAllJson(
        JSON.stringify({ app: 'baibai', version: 1, sessions: [valid, invalid] }),
      ),
    ).rejects.toThrow(/不完整/);
    expect(await listSessions()).toHaveLength(0);
  });

  it('导入拒绝：清单条目缺名字', async () => {
    await expect(
      importAllJson(
        JSON.stringify({
          app: 'baibai',
          version: 1,
          sessions: [],
          plans: [{ year: 2026, createdAt: 0, updatedAt: 0, items: [{ pos: null }] }],
        }),
      ),
    ).rejects.toThrow(/清单/);
  });
});
