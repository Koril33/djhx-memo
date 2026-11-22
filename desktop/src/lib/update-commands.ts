import { get, writable } from 'svelte/store';

// Native work outlives a settings tab. Keep its receipt barrier across component remounts.
export const updateCommandPending = writable(false);
export const sourceChangePending = writable(false);
let receipt: Promise<unknown> | undefined;
export async function runUpdateCommand<T>(work: () => Promise<T>): Promise<T | undefined> {
  if (get(updateCommandPending) || get(sourceChangePending)) return undefined;
  return execute(work);
}
async function execute<T>(work: () => Promise<T>): Promise<T> {
  updateCommandPending.set(true);
  const job = (async () => {
    try {
      return await work();
    } finally {
      updateCommandPending.set(false);
    }
  })();
  receipt = job.catch(() => undefined);
  return job;
}
/** A source edit may cancel an active transfer, but cannot overlap its receipt. */
export async function runSourceChange<T>(
  cancel: () => Promise<unknown>,
  work: () => Promise<T>,
): Promise<T | undefined> {
  if (get(sourceChangePending)) return undefined;
  sourceChangePending.set(true);
  try {
    await cancel();
    await receipt;
    return await execute(work);
  } finally {
    sourceChangePending.set(false);
  }
}
