import { describe, expect, it } from 'vitest';
import {
  createTranslator,
  LocalizedError,
  messages,
  englishSingularMessages,
  type CountMessageKey,
} from './i18n';
import {
  DEFAULT_SERVER_URL,
  PREFERENCES_KEY,
  defaultPreferences,
  formatTime,
  loadPreferences,
  resolveLanguage,
  resolveTheme,
  savePreferences,
  profileLabel,
} from './preferences';
import { coreErrorText, errorText, InvokeError } from './bridge';
import { renderMarkdown } from './markdown';
import type { Profile } from './types';

const profile: Profile = {
  id: 'local',
  kind: 'local',
  label: '本机笔记',
  server_url: null,
  user_id: null,
  username: null,
  device_id: null,
  read_only: false,
};

describe('device presentation preferences', () => {
  it('defaults independently to system language/theme and Shanghai, without touching account configuration', () => {
    expect(loadPreferences({ getItem: () => null })).toEqual(defaultPreferences);
    expect(DEFAULT_SERVER_URL).toBe('https://memo.djhx.site');
    expect(resolveLanguage('system', 'zh-Hant-TW')).toBe('zh-CN');
    expect(resolveLanguage('system', 'zh_CN')).toBe('zh-CN');
    expect(resolveLanguage('system', 'fr-FR')).toBe('en');
    expect(resolveLanguage('en', 'zh-CN')).toBe('en');
    expect(resolveTheme('system', true)).toBe('dark');
    expect(resolveTheme('system', false)).toBe('light');
    expect(resolveTheme('light', true)).toBe('light');
    expect(resolveTheme('dark', false)).toBe('dark');
  });
  it('round-trips only validated presentation values and recovers malformed/unknown stored values', () => {
    const data = new Map<string, string>();
    const storage = {
      getItem: (key: string) => data.get(key) ?? null,
      setItem: (key: string, value: string) => {
        data.set(key, value);
      },
    };
    const chosen = { language: 'en', theme: 'dark', timeZone: 'UTC' } as const;
    savePreferences(storage, chosen);
    expect(loadPreferences(storage)).toEqual(chosen);
    expect([...data.keys()]).toEqual([PREFERENCES_KEY]);
    data.set(PREFERENCES_KEY, '{malformed');
    expect(loadPreferences(storage)).toEqual(defaultPreferences);
    data.set(PREFERENCES_KEY, JSON.stringify({ language: 'de', theme: 'sepia', timeZone: 'BAD' }));
    expect(loadPreferences(storage)).toEqual(defaultPreferences);
    expect(
      loadPreferences({
        getItem: () => {
          throw new Error('unavailable');
        },
      }),
    ).toEqual(defaultPreferences);
    expect(() =>
      savePreferences(
        {
          setItem: () => {
            throw new Error('full');
          },
        },
        chosen,
      ),
    ).toThrow('full');
  });
  it('formats Shanghai across the UTC day boundary and keeps locale independent of time zone', () => {
    const utc = '2026-11-07T20:05:00Z';
    expect(formatTime(utc, 'en', 'Asia/Shanghai', '')).toContain('Nov 8, 2026, 04:05');
    expect(formatTime(utc, 'en', 'UTC', '')).toContain('Nov 7, 2026, 20:05');
    expect(formatTime(utc, 'zh-CN', 'Asia/Shanghai', '')).toContain('11月8日 04:05');
    expect(formatTime(utc, 'en', 'system', '')).toBe(
      new Intl.DateTimeFormat('en', {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        hourCycle: 'h23',
      }).format(new Date(utc)),
    );
    expect(formatTime(null, 'en', 'UTC', 'No record')).toBe('No record');
    expect(formatTime('invalid', 'en', 'UTC', '')).toBe('invalid');
  });
  it('translates generated labels and recovery timestamps while retaining author-controlled account names', () => {
    const t = createTranslator('en');
    expect(profileLabel(profile, t, 'en', 'Asia/Shanghai')).toBe('Local notes');
    const account = {
      ...profile,
      id: 'account',
      kind: 'account',
      label: '中文作者 · https://custom.test',
      server_url: 'https://custom.test',
    } as const;
    expect(profileLabel(account, t, 'en', 'UTC')).toBe(account.label);
    const recovery = {
      ...account,
      kind: 'recovery',
      username: 'owner',
      label: '恢复空间 · owner · 2026-11-07T20:05:00Z',
    } as const;
    expect(profileLabel(recovery, t, 'en', 'Asia/Shanghai')).toBe(
      'Recovery space · owner · Nov 8, 2026, 04:05',
    );
  });
});

describe('bilingual UI contract', () => {
  it('keeps complete nonempty English/Chinese catalogues and identical interpolation parameters', () => {
    for (const [key, translations] of Object.entries(messages)) {
      expect(Object.keys(translations), key).toEqual(['zh-CN', 'en']);
      expect(translations['zh-CN'].trim(), key).not.toBe('');
      expect(translations.en.trim(), key).not.toBe('');
      const parameters = (value: string) =>
        [...value.matchAll(/\{(\w+)\}/g)].map((match) => match[1]).sort();
      expect(parameters(translations.en), key).toEqual(parameters(translations['zh-CN']));
    }
    expect(createTranslator('en')('characters', { count: 123 })).toBe('123 characters');
  });
  it('uses complete English singular forms for one and plural forms for zero or two', () => {
    const expected = {
      selectedCount: ['1 selected', '0 selected', '2 selected'],
      batchResult: [
        '1 moved to trash · {failed} failed',
        '0 moved to trash · {failed} failed',
        '2 moved to trash · {failed} failed',
      ],
      undoResult: [
        '1 restored · {failed} failed',
        '0 restored · {failed} failed',
        '2 restored · {failed} failed',
      ],
      pending: ['Pending · 1', 'Pending · 0', 'Pending · 2'],
      imported: [
        'Copied 1 note. The original remains in the source space.',
        'Copied 0 notes. Originals remain in the source space.',
        'Copied 2 notes. Originals remain in the source space.',
      ],
      records: ['1 note', '0 notes', '2 notes'],
      matches: ['1 match', '0 matches', '2 matches'],
      characters: ['1 character', '0 characters', '2 characters'],
      noteCountSuffix: [' (1 note)', ' (0 notes)', ' (2 notes)'],
      importCount: ['Copy 1 local note to', 'Copy 0 local notes to', 'Copy 2 local notes to'],
      importSubmit: ['Import 1 local note', 'Import 0 local notes', 'Import 2 local notes'],
    } satisfies Record<CountMessageKey, [string, string, string]>;
    const countKeys = Object.entries(messages)
      .filter(([, value]) => value.en.includes('{count}'))
      .map(([key]) => key);
    expect(Object.keys(englishSingularMessages).sort()).toEqual(countKeys.sort());
    const en = createTranslator('en');
    const zh = createTranslator('zh-CN');
    for (const key of Object.keys(expected) as CountMessageKey[]) {
      expect(en(key, { count: 1 }), key).toBe(expected[key][0]);
      expect(en(key, { count: 0 }), key).toBe(expected[key][1]);
      expect(en(key, { count: 2 }), key).toBe(expected[key][2]);
      // Character counts are passed as locale-formatted strings in the actual footer.
      expect(en(key, { count: '1' }), key).toBe(expected[key][0]);
      const parameters = (value: string) =>
        [...value.matchAll(/\{(\w+)\}/g)].map((match) => match[1]).sort();
      expect(parameters(englishSingularMessages[key]), key).toEqual(parameters(messages[key].en));
      expect(parameters(englishSingularMessages[key]), key).toEqual(
        parameters(messages[key]['zh-CN']),
      );
      for (const count of [0, 1, 2])
        expect(zh(key, { count }), key).toBe(
          messages[key]['zh-CN'].replace('{count}', String(count)),
        );
    }
    expect(en('characters', { count: '1,000' })).toBe('1,000 characters');
    expect(en('characters', { count: 1000 })).toBe('1000 characters');
    expect(en('settings')).toBe(messages.settings.en);
  });
  it('maps known errors into the current language without leaking arbitrary native/server payloads', () => {
    const detail = {
      code: 'AUTH_INVALID_CREDENTIALS',
      message: 'private payload must not appear',
      retryable: false,
    };
    expect(errorText(new InvokeError(detail), 'en')).toContain('Incorrect username or password');
    expect(coreErrorText(detail, 'zh-CN')).toContain('用户名或密码不正确');
    expect(errorText(new Error('secret draft'), 'en')).not.toContain('secret draft');
    expect(errorText(new LocalizedError('invalidPassword'), 'en')).toBe(
      'Use a password of 12–128 characters.',
    );
    expect(errorText('目标文件已存在，请使用新的文件名；已有文件未改动。', 'en')).toContain(
      'destination exists',
    );
  });
  it('does not echo unknown error codes or look up inherited object properties', () => {
    for (const language of ['en', 'zh-CN'] as const) {
      const generic = createTranslator(language)('genericError');
      for (const code of [
        'UNKNOWN_SERVER_ERROR',
        'private 中文 payload',
        'x'.repeat(4096),
        'constructor',
        'toString',
        'valueOf',
        '__proto__',
      ]) {
        const detail = { code, message: 'private message', retryable: false };
        expect(coreErrorText(detail, language)).toBe(generic);
        expect(errorText(new InvokeError(detail), language)).toBe(generic);
        expect(errorText(code, language)).toBe(generic);
        expect(errorText(new Error(code), language)).toBe(generic);
      }
      expect(
        coreErrorText(
          { code: 'AUTH_REQUIRED', message: 'private message', retryable: false },
          language,
        ),
      ).toBe(`${createTranslator(language)('errorAuthRequired')} (AUTH_REQUIRED)`);
    }
  });
  it('localizes generated Markdown controls while leaving note text and image descriptions unchanged', () => {
    const source = '- [ ] 中文作者 text\n\n![](https://images.test/image.png)';
    const en = renderMarkdown(source, true, '1', 'en');
    const zh = renderMarkdown(source, true, '1', 'zh-CN');
    expect(en).toContain('aria-label="Mark complete"');
    expect(zh).toContain('aria-label="标记完成"');
    expect(en).toContain('Automatic loading blocked');
    expect(zh).toContain('已禁止自动加载');
    expect(en).toContain('中文作者 text');
    expect(en).not.toContain('<img');
  });
});
