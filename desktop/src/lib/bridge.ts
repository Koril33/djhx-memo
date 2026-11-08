import { invoke } from '@tauri-apps/api/core';
import { createTranslator, LocalizedError, type Language, type MessageKey } from './i18n';
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
const errorKeys: Record<string, MessageKey> = {
  AUTH_INVALID_CREDENTIALS: 'errorAuth',
  AUTH_REQUIRED: 'errorAuthRequired',
  INVITE_INVALID: 'errorInvite',
  USERNAME_TAKEN: 'errorUsername',
  RATE_LIMITED: 'errorRate',
  DEVICE_ID_UNAVAILABLE: 'errorDevice',
  SYNC_EPOCH_MISMATCH: 'errorRecovery',
  RECOVERY_REQUIRED: 'errorRecovery',
  CURSOR_INVALID: 'errorCursor',
  PROTOCOL_UNSUPPORTED: 'errorProtocol',
  INVALID_REQUEST: 'errorInvalid',
  LOCAL_STORAGE_FAILED: 'errorStorage',
  LOCAL_SCHEMA_TOO_NEW: 'errorSchema',
  NOT_FOUND: 'errorMissing',
  NETWORK_UNAVAILABLE: 'errorNetwork',
  SERVER_BUSY: 'errorBusy',
  NOTE_TOO_LARGE: 'errorLarge',
  LOCAL_GENERATION_CONFLICT: 'errorGeneration',
  PROFILE_CHANGED: 'errorSession',
  SESSION_CHANGED: 'errorSession',
  EXPORT_FAILED: 'errorExport',
};
// Native commands expose fixed safe messages. Translate the known surface and never display
// arbitrary native/server error payloads, which could contain user data or untranslated text.
const nativeErrorKeys: Record<string, MessageKey> = {
  '回收站笔记需先恢复才能编辑。': 'errorDeleted',
  '目标文件已存在，请使用新的文件名；已有文件未改动。': 'errorFileExists',
  '没有权限写入所选目录，请重新选择。': 'errorPermission',
  '请选择有效的导出目录。': 'errorLocalPath',
  '请选择本地文件路径。': 'errorLocalPath',
  '无法打开系统浏览器。': 'errorBrowser',
  '仅允许打开 HTTP 或 HTTPS 链接。': 'errorLink',
  '窗口操作未完成。': 'errorWindow',
  '窗口操作已过期。': 'errorWindow',
  '窗口隐藏失败。': 'errorWindow',
  '导出文件写入失败，请检查磁盘空间及目录是否支持原子创建。': 'errorExport',
  '无法创建导出文件。': 'errorExport',
  '导出响应无效。': 'errorExport',
  '核心导出失败。': 'errorExport',
  '导出内容缺失。': 'errorExport',
  '导出内容无效。': 'errorExport',
  '导出任务未完成。': 'errorExport',
  '救援导出未完成。': 'errorExport',
};
export function coreErrorText(error: CoreError, language: Language): string {
  const t = createTranslator(language);
  if (!Object.hasOwn(errorKeys, error.code)) return t('genericError');
  return `${t(errorKeys[error.code])} (${error.code})`;
}
export function errorText(error: unknown, language: Language = 'zh-CN'): string {
  if (error instanceof InvokeError) return coreErrorText(error.detail, language);
  const t = createTranslator(language);
  if (error instanceof LocalizedError) return t(error.key);
  const message = typeof error === 'string' ? error : error instanceof Error ? error.message : '';
  return t(Object.hasOwn(nativeErrorKeys, message) ? nativeErrorKeys[message] : 'genericError');
}
export const native = invoke;
