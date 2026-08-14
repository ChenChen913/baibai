import { describe, expect, it } from 'vitest';
import { cnyLabel, fromDateStr, toDateStr } from '../src/cny.js';

describe('cnyLabel 春节标签', () => {
  it('春节当天显示大年初一', () => {
    expect(cnyLabel(new Date(2026, 1, 17))).toBe('大年初一');
  });
  it('初二到初十', () => {
    expect(cnyLabel(new Date(2026, 1, 18))).toBe('大年初二');
    expect(cnyLabel(new Date(2026, 1, 21))).toBe('大年初五');
    expect(cnyLabel(new Date(2026, 1, 26))).toBe('大年初十');
  });
  it('除夕', () => {
    expect(cnyLabel(new Date(2026, 1, 16))).toBe('除夕');
  });
  it('初十之后回退月日', () => {
    expect(cnyLabel(new Date(2026, 1, 27))).toBe('2月27日');
  });
  it('非春节时段回退月日', () => {
    expect(cnyLabel(new Date(2026, 7, 14))).toBe('8月14日');
  });
  it('跨年边界', () => {
    expect(cnyLabel(new Date(2025, 0, 29))).toBe('大年初一');
    expect(cnyLabel(new Date(2025, 1, 7))).toBe('大年初十');
    expect(cnyLabel(new Date(2025, 1, 8))).toBe('2月8日');
    expect(cnyLabel(new Date(2027, 1, 6))).toBe('大年初一');
  });
  it('表外年份回退月日', () => {
    expect(cnyLabel(new Date(2019, 0, 1))).toBe('1月1日');
  });
  it('日期字符串互转', () => {
    expect(toDateStr(new Date(2026, 1, 17))).toBe('2026-02-17');
    const d = fromDateStr('2026-02-17');
    expect(d.getFullYear()).toBe(2026);
    expect(d.getMonth()).toBe(1);
    expect(d.getDate()).toBe(17);
  });
});
