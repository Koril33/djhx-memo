import { describe, expect, it } from 'vitest';
import { authenticationAccepted } from './account-state';
import type { AppState } from './types';

const snapshot = (id: string, sync: AppState['sync']['state']) =>
  ({ profile: { id, kind: 'account' }, sync: { state: sync } }) as AppState;

describe('authentication receipt', () => {
  it('accepts only the account returned by the successful command', () => {
    expect(authenticationAccepted('a', snapshot('a', 'synced'))).toBe(true);
    expect(authenticationAccepted('a', snapshot('a', 'offline'))).toBe(true);
    expect(authenticationAccepted('a', snapshot('b', 'synced'))).toBe(false);
    expect(authenticationAccepted(undefined, snapshot('a', 'synced'))).toBe(false);
    expect(authenticationAccepted('a', null)).toBe(false);
  });
  it('keeps the form when a refreshed session has already expired', () => {
    expect(authenticationAccepted('a', snapshot('a', 'auth_required'))).toBe(false);
  });
});
