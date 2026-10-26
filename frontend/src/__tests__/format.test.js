import { describe, expect, it } from 'vitest';
import { formatMoney, formatTimestamp, humanizeEventType } from '../format';

describe('formatMoney', () => {
  it('formats numbers and numeric strings as US dollars', () => {
    expect(formatMoney(1842.77)).toBe('$1,842.77');
    expect(formatMoney('100')).toBe('$100.00');
  });

  it('returns an empty string for missing or invalid values', () => {
    expect(formatMoney(null)).toBe('');
    expect(formatMoney('abc')).toBe('');
  });
});

describe('formatTimestamp', () => {
  it('renders local time as yyyy-MM-dd HH:mm:ss', () => {
    const local = new Date(2026, 0, 5, 9, 7, 3);
    expect(formatTimestamp(local.toISOString())).toBe('2026-01-05 09:07:03');
  });

  it('renders a dash for missing or invalid timestamps', () => {
    expect(formatTimestamp(null)).toBe('—');
    expect(formatTimestamp('not a date')).toBe('—');
  });
});

describe('humanizeEventType', () => {
  it('turns enum names into sentence case', () => {
    expect(humanizeEventType('HUMAN_APPROVAL_REQUIRED')).toBe('Human approval required');
  });
});
