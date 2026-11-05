import { describe, expect, it } from 'vitest';
import { PreviewState, taskSnapshotMatches } from './preview-state';

describe('task preview snapshot guard', () => {
  it('rejects an old task line after input inserts a new line while flush is pending', () => {
    const state = new PreviewState();
    const before = {
      source: '- [ ] first\n- [ ] second',
      profileId: 'local',
      documentKey: 1,
      noteId: 'note-a',
    };
    const snapshot = state.capture(before);
    const after = { ...before, source: 'inserted\n' + before.source };
    expect(taskSnapshotMatches(snapshot, snapshot.version, after)).toBe(false);
    const refreshed = state.capture(after);
    expect(taskSnapshotMatches(refreshed, snapshot.version, after)).toBe(false);
    expect(taskSnapshotMatches(refreshed, refreshed.version, after)).toBe(true);
  });
  it('allows a committed draft with identical source and rejects profile/document changes', () => {
    const state = new PreviewState();
    const draft = { source: '- [ ] draft', profileId: 'local', documentKey: 1, noteId: null };
    const snapshot = state.capture(draft);
    expect(taskSnapshotMatches(snapshot, snapshot.version, { ...draft, noteId: 'created' })).toBe(
      true,
    );
    expect(taskSnapshotMatches(snapshot, snapshot.version, { ...draft, documentKey: 2 })).toBe(
      false,
    );
    expect(
      taskSnapshotMatches(snapshot, snapshot.version, { ...draft, profileId: 'account' }),
    ).toBe(false);
  });
});
