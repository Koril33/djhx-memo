import { describe, expect, it } from 'vitest';
import { clearRevoking, receiptIsCurrent } from './devices';

describe('device receipt ownership', () => {
  it('accepts only the latest session for the same profile', () => {
    expect(receiptIsCurrent(3, 3, 'acct', 'acct')).toBe(true);
    expect(receiptIsCurrent(2, 3, 'acct', 'acct')).toBe(false);
    expect(receiptIsCurrent(3, 3, 'acct', 'local')).toBe(false);
    expect(receiptIsCurrent(3, 3, 'acct', undefined)).toBe(false);
  });

  it('clears revoking only when the finished revoke is still active', () => {
    expect(clearRevoking('dev-a', 'dev-a')).toBeNull();
    expect(clearRevoking('dev-b', 'dev-a')).toBe('dev-b');
    expect(clearRevoking(null, 'dev-a')).toBeNull();
  });
});
