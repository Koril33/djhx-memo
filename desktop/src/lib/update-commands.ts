import { get, writable } from 'svelte/store';

// Native work outlives a settings tab. Keep its receipt barrier across component remounts.
export const updateCommandPending = writable(false);
export async function runUpdateCommand<T>(work: () => Promise<T>): Promise<T | undefined> {
  if (get(updateCommandPending)) return undefined;
  updateCommandPending.set(true);
  try {
    return await work();
  } finally {
    updateCommandPending.set(false);
  }
}
