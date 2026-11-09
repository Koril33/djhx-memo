export interface PreviewContext {
  source: string;
  profileId: string;
  documentKey: number;
  noteId: string | null;
}
export interface PreviewSnapshot extends PreviewContext {
  version: string;
}

export class PreviewState {
  private serial = 0;
  private previous: PreviewSnapshot | null = null;

  capture(context: PreviewContext): PreviewSnapshot {
    if (
      this.previous &&
      this.previous.source === context.source &&
      this.previous.profileId === context.profileId &&
      this.previous.documentKey === context.documentKey &&
      this.previous.noteId === context.noteId
    )
      return this.previous;
    this.previous = { ...context, version: String(++this.serial) };
    return this.previous;
  }
}

export function taskSnapshotMatches(
  snapshot: PreviewSnapshot,
  buttonVersion: string | undefined,
  current: PreviewContext,
): boolean {
  return (
    buttonVersion === snapshot.version &&
    snapshot.source === current.source &&
    snapshot.profileId === current.profileId &&
    snapshot.documentKey === current.documentKey &&
    (snapshot.noteId === null || snapshot.noteId === current.noteId)
  );
}
