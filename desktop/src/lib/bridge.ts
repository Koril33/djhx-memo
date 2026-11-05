import { invoke } from '@tauri-apps/api/core';
import type { CoreError, Request } from './types';

export class InvokeError extends Error {
  constructor(public readonly detail: CoreError) {
    super(detail.message);
  }
}

export async function call<T>(request: Request): Promise<T> {
  const raw = await invoke<string>('core_invoke', { requestJson: JSON.stringify(request) });
  const result = JSON.parse(raw) as { ok: boolean; data: T; error: CoreError };
  if (!result.ok) throw new InvokeError(result.error);
  return result.data;
}

export function errorText(error: unknown): string {
  if (error instanceof InvokeError) return `${error.message}（${error.detail.code}）`;
  return error instanceof Error ? error.message : '操作未完成，请重试。';
}

export const native = invoke;
