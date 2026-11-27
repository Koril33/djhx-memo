/** A device receipt is current only when its session and profile still match.
 * Every refresh gets a monotonic session; a profile change or a newer refresh
 * advances it, so a late receipt must be dropped rather than applied. */
export function receiptIsCurrent(
  receiptSession: number,
  currentSession: number,
  receiptProfile: string,
  currentProfile: string | undefined,
): boolean {
  return receiptSession === currentSession && receiptProfile === currentProfile;
}

/** Clear the revoking flag only when the finished revoke is still the active
 * one. A newer revoke (or invalidateDevices) must never be clobbered. */
export function clearRevoking(current: string | null, finished: string): string | null {
  return current === finished ? null : current;
}
