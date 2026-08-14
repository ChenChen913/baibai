/** 春节日期与「大年初X」标签（纯函数；安卓版 core/Cny.kt 同一张表，两侧口径一致） */

/** 春节（正月初一）公历日期表：2020–2037（历法公布值） */
export const SPRING_FESTIVAL: Record<number, string> = {
  2020: '2020-01-25',
  2021: '2021-02-12',
  2022: '2022-02-01',
  2023: '2023-01-22',
  2024: '2024-02-10',
  2025: '2025-01-29',
  2026: '2026-02-17',
  2027: '2027-02-06',
  2028: '2028-01-26',
  2029: '2029-02-13',
  2030: '2030-02-03',
  2031: '2031-01-23',
  2032: '2032-02-11',
  2033: '2033-01-31',
  2034: '2034-02-19',
  2035: '2035-02-08',
  2036: '2036-01-28',
  2037: '2037-02-15',
};

const MAX_DAY = 10; // 初一到初十

const CN_NUM: Record<number, string> = {
  1: '一', 2: '二', 3: '三', 4: '四', 5: '五',
  6: '六', 7: '七', 8: '八', 9: '九', 10: '十',
};

function fmtMonthDay(d: Date): string {
  return d.getMonth() + 1 + '月' + d.getDate() + '日';
}

/** 日期 → 拜年标签：除夕 / 大年初X（初一到初十）；其余回退「M月d日」 */
export function cnyLabel(date: Date): string {
  const y = date.getFullYear();
  const cny = SPRING_FESTIVAL[y];
  if (!cny) return fmtMonthDay(date);
  const cnyDate = new Date(cny + 'T00:00:00');
  const diff = Math.round((date.getTime() - cnyDate.getTime()) / 86400000);
  if (diff === -1) return '除夕';
  if (diff >= 0 && diff < MAX_DAY) return '大年初' + (CN_NUM[diff + 1] ?? String(diff + 1));
  return fmtMonthDay(date);
}

/** 本地日期 → YYYY-MM-DD（与 session.date 口径一致） */
export function toDateStr(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return y + '-' + m + '-' + day;
}

/** YYYY-MM-DD → 本地日期（当天 0 点） */
export function fromDateStr(s: string): Date {
  const parts = s.split('-').map((x) => Number(x));
  return new Date(parts[0], (parts[1] ?? 1) - 1, parts[2] ?? 1);
}
