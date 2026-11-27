import type { Language, Translator } from './i18n';
import type { Profile } from './types';

export const DEFAULT_SERVER_URL = 'https://memo.djhx.site';
export const PREFERENCES_KEY = 'djhx-memo.presentation.v1';
export interface Preferences {
  language: 'system' | Language;
  theme: 'system' | 'light' | 'dark';
  timeZone: 'Asia/Shanghai' | 'UTC' | 'system';
  markdownLintEnabled: boolean;
  splitScrollSync: boolean;
  editorMode: 'source' | 'preview' | 'split';
  outlineVisible: boolean;
}
export const defaultPreferences: Readonly<Preferences> = {
  language: 'system',
  theme: 'system',
  timeZone: 'Asia/Shanghai',
  markdownLintEnabled: false,
  splitScrollSync: true,
  editorMode: 'source',
  outlineVisible: false,
};
type StorageReader = Pick<Storage, 'getItem'>;
type StorageWriter = Pick<Storage, 'setItem'>;
export function loadPreferences(storage: StorageReader): Preferences {
  try {
    const value = JSON.parse(storage.getItem(PREFERENCES_KEY) ?? '{}') as Partial<Preferences>;
    return {
      markdownLintEnabled:
        typeof value?.markdownLintEnabled === 'boolean' ? value.markdownLintEnabled : false,
      splitScrollSync: typeof value?.splitScrollSync === 'boolean' ? value.splitScrollSync : true,
      editorMode: ['source', 'preview', 'split'].includes(value?.editorMode ?? '')
        ? value.editorMode!
        : defaultPreferences.editorMode,
      outlineVisible: typeof value?.outlineVisible === 'boolean' ? value.outlineVisible : false,
      language: ['system', 'zh-CN', 'en'].includes(value?.language ?? '')
        ? value.language!
        : defaultPreferences.language,
      theme: ['system', 'light', 'dark'].includes(value?.theme ?? '')
        ? value.theme!
        : defaultPreferences.theme,
      timeZone: ['Asia/Shanghai', 'UTC', 'system'].includes(value?.timeZone ?? '')
        ? value.timeZone!
        : defaultPreferences.timeZone,
    };
  } catch {
    return { ...defaultPreferences };
  }
}
export function savePreferences(storage: StorageWriter, value: Preferences): void {
  storage.setItem(PREFERENCES_KEY, JSON.stringify(value));
}
export function resolveLanguage(
  preference: Preferences['language'],
  systemLanguage: string,
): Language {
  return preference === 'system'
    ? /^zh(?:[-_]|$)/i.test(systemLanguage)
      ? 'zh-CN'
      : 'en'
    : preference;
}
export function resolveTheme(
  preference: Preferences['theme'],
  systemDark: boolean,
): 'light' | 'dark' {
  return preference === 'system' ? (systemDark ? 'dark' : 'light') : preference;
}
export function formatTime(
  value: string | null,
  language: Language,
  timeZone: Preferences['timeZone'],
  empty: string,
): string {
  if (!value) return empty;
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return value;
  return new Intl.DateTimeFormat(language, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    ...(timeZone === 'system' ? {} : { timeZone }),
  }).format(date);
}
export function profileLabel(
  profile: Profile,
  t: Translator,
  language: Language,
  timeZone: Preferences['timeZone'],
): string {
  if (profile.kind === 'local') return t('localNotes');
  if (profile.kind === 'recovery') {
    const timestamp = profile.label.split(' · ').at(-1) ?? '';
    const date = new Date(timestamp);
    return [
      t('recoverySpace'),
      profile.username,
      !Number.isNaN(date.valueOf()) ? formatTime(timestamp, language, timeZone, '') : null,
    ]
      .filter(Boolean)
      .join(' · ');
  }
  return profile.label;
}

/** Safe host plus base path from a server URL, without userinfo, query or fragment. */
export function serverHost(serverUrl: string | null | undefined): string | null {
  if (!serverUrl) return null;
  try {
    const url = new URL(serverUrl);
    const path = url.pathname.replace(/\/+$/, '');
    if (!path || path === '/') return url.host;
    return `${url.host}${path}`;
  } catch {
    return null;
  }
}

/**
 * Search-hit source label. Local spaces show the fixed local-space text;
 * account spaces show `username · server host`, falling back to the profile
 * label when the username is missing and to a generic account-space text when
 * no displayable host is available. Never uses credentials or filesystem paths.
 */
export function searchSourceLabel(
  profile: Profile | undefined,
  kind: 'local' | 'account',
  t: Translator,
): string {
  if (kind === 'local') return t('localSpace');
  const host = serverHost(profile?.server_url);
  const name = profile?.username ?? profile?.label ?? null;
  if (name && host) return `${name} · ${host}`;
  if (name) return name;
  return t('accountSpace');
}
