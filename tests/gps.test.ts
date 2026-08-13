import { describe, expect, it } from 'vitest';
import { describeGpsError } from '../src/gps.js';

describe('describeGpsError（定位错误分类）', () => {
  it('错误码映射', () => {
    expect(describeGpsError(1)).toBe('denied'); // PERMISSION_DENIED
    expect(describeGpsError(2)).toBe('unavailable'); // POSITION_UNAVAILABLE
    expect(describeGpsError(3)).toBe('timeout'); // TIMEOUT
    expect(describeGpsError(0)).toBe('unavailable');
    expect(describeGpsError(99)).toBe('unavailable');
  });
});
