import type { AppState } from './types';

/** A saved account is not a session: only an explicit receipt in the active space closes login. */
export function authenticationAccepted(
  targetId: string | undefined,
  state: AppState | null,
): boolean {
  return !!targetId && state?.profile.id === targetId && state.sync.state !== 'auth_required';
}
