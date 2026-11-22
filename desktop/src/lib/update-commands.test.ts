import { expect, it } from 'vitest';
import { get } from 'svelte/store';
import {
  runUpdateCommand,
  runSourceChange,
  sourceChangePending,
  updateCommandPending,
} from './update-commands';

it('retains the command barrier while a settings subscriber unmounts and remounts', async () => {
  let finish!: (value: string) => void;
  const pending = new Promise<string>((resolve) => {
    finish = resolve;
  });
  const unsubscribe = updateCommandPending.subscribe(() => {});
  const first = runUpdateCommand(() => pending);
  unsubscribe();
  let remounted = false;
  const close = updateCommandPending.subscribe((value) => {
    remounted = value;
  });
  expect(remounted).toBe(true);
  let submitted = false;
  expect(
    await runUpdateCommand(async () => {
      submitted = true;
    }),
  ).toBeUndefined();
  expect(submitted).toBe(false);
  finish('ready');
  expect(await first).toBe('ready');
  expect(remounted).toBe(false);
  close();
});

it('releases a failed command for retry without concealing its error', async () => {
  await expect(
    runUpdateCommand(async () => {
      throw new Error('fixture');
    }),
  ).rejects.toThrow('fixture');
  expect(get(updateCommandPending)).toBe(false);
  expect(await runUpdateCommand(async () => 'retried')).toBe('retried');
});

it('source switching cancels first, survives remount and waits for the real transfer receipt', async () => {
  let finish!: () => void;
  const transfer = runUpdateCommand(
    () =>
      new Promise<void>((resolve) => {
        finish = resolve;
      }),
  );
  const events: string[] = [];
  const change = runSourceChange(
    async () => {
      events.push('cancel');
    },
    async () => {
      events.push('source');
      return 'github';
    },
  );
  await Promise.resolve();
  expect(events).toEqual(['cancel']);
  expect(get(sourceChangePending)).toBe(true);
  expect(await runUpdateCommand(async () => 'overlap')).toBeUndefined();
  finish();
  await transfer;
  expect(await change).toBe('github');
  expect(events).toEqual(['cancel', 'source']);
  expect(get(sourceChangePending)).toBe(false);
});
