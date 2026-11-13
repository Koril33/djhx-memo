<script lang="ts">
  import { onMount, onDestroy } from 'svelte';
  import { listen, type UnlistenFn } from '@tauri-apps/api/event';
  import Editor from './Editor.svelte';
  import Icon from './Icon.svelte';
  import Updates from './Updates.svelte';
  import { call, native, errorText, coreErrorText } from './lib/bridge';
  import { EditorSession } from './lib/editor-session';
  import { renderMarkdown } from './lib/markdown';
  import type { CodeHighlights } from './lib/code-highlights';
  import { PreviewState, taskSnapshotMatches } from './lib/preview-state';
  import {
    createTranslator,
    LocalizedError,
    type Message,
    type MessageKey,
    type MessageArgs,
  } from './lib/i18n';
  import {
    DEFAULT_SERVER_URL,
    defaultPreferences,
    loadPreferences,
    savePreferences,
    resolveLanguage,
    resolveTheme,
    formatTime,
    profileLabel,
    type Preferences,
  } from './lib/preferences';
  import type { AppState, Device, Note, Profile, RuntimeInfo } from './lib/types';

  let preferences: Preferences = (() => {
    try {
      return loadPreferences(localStorage);
    } catch {
      return { ...defaultPreferences };
    }
  })();
  let listVisible = (() => {
    try {
      return localStorage.getItem('djhx-memo.note-list.v1') !== 'hidden';
    } catch {
      return true;
    }
  })();
  let purgeConfirmation: { id: string; profile: string; generation: string } | null = null;
  function setListVisible(visible: boolean): void {
    listVisible = visible;
    closeMenus();
    try {
      localStorage.setItem('djhx-memo.note-list.v1', visible ? 'visible' : 'hidden');
    } catch {
      /* Presentation stays usable without storage. */
    }
  }
  async function confirmPurge(): Promise<void> {
    const target = purgeConfirmation;
    if (!target) return;
    await editor.flush();
    const result = await call<Note>({
      action: 'purge',
      profile_id: target.profile,
      id: target.id,
      expected_generation: target.generation,
    });
    purgeConfirmation = null;
    await editor.open(result.purge_state === 'purged' ? null : result);
    if (result.purge_state === 'purged') documentKey++;
    await refreshState(true);
    notify(result.purge_state === 'purged' ? 'purgeDone' : 'purgeRequested');
  }
  let systemLanguage = navigator.language;
  const colorScheme = window.matchMedia('(prefers-color-scheme: dark)');
  let browserDark = colorScheme.matches;
  let nativeSystemDark: boolean | null = null;
  let queryingAppearance = false;
  $: systemDark = nativeSystemDark ?? browserDark;
  async function refreshSystemAppearance(): Promise<void> {
    if (queryingAppearance) return;
    queryingAppearance = true;
    try {
      nativeSystemDark = await native<boolean | null>('system_appearance');
    } catch {
      nativeSystemDark = null;
    } finally {
      queryingAppearance = false;
    }
  }
  $: language = resolveLanguage(preferences.language, systemLanguage);
  $: activeTheme = resolveTheme(preferences.theme, systemDark);
  $: t = createTranslator(language);
  $: {
    document.documentElement.lang = language;
    document.documentElement.dataset.theme = activeTheme;
    document.documentElement.style.colorScheme = activeTheme;
    void native('set_presentation', {
      language,
      theme: preferences.theme,
      systemDark: nativeSystemDark,
    }).catch(() => {});
  }
  function updatePreferences(): void {
    preferences = { ...preferences };
    if (preferences.theme === 'system') void refreshSystemAppearance();
    try {
      savePreferences(localStorage, preferences);
    } catch {
      failure = new LocalizedError('preferencesFailed');
    }
  }

  let state: AppState | null = null;
  let runtime: RuntimeInfo | null = null;
  let notes: Note[] = [];
  let selecting = false;
  let selection = new Set<string>();
  type BatchResult = { results: { id: string; ok: boolean; note?: Note }[] };
  let undoGroup: { profile: string; notes: Note[] } | null = null;
  let undoTimer: ReturnType<typeof setTimeout> | undefined;
  function clearListActions(): void {
    selecting = false;
    selection = new Set();
  }
  function clearUndo(): void {
    clearTimeout(undoTimer);
    undoGroup = null;
  }
  function toggleSelection(id: string): void {
    selection = new Set(selection);
    if (selection.has(id)) selection.delete(id);
    else selection.add(id);
  }
  function restartUndoTimer(): void {
    clearTimeout(undoTimer);
    if (undoGroup) undoTimer = setTimeout(clearUndo, 8000);
  }
  function offerUndo(profile: string, saved: Note[]): void {
    if (!saved.length) return;
    clearUndo();
    undoGroup = { profile, notes: saved };
    // Keep the action available while focused/hovered for keyboard and assistive use.
    restartUndoTimer();
  }
  async function batchTrash(targets = notes.filter((n) => selection.has(n.id))): Promise<void> {
    const profile = state!.profile.id;
    await editor.finishLifecycle(async () => {
      targets = targets.map((n) => (n.id === editor.note?.id ? editor.note! : n));
      const result = await call<BatchResult>({
        action: 'batch_set_deleted',
        profile_id: profile,
        deleted: true,
        items: targets.map((n) => ({ id: n.id, expected_generation: n.generation })),
      });
      const saved = result.results.flatMap((r) => (r.ok && r.note ? [r.note] : []));
      selection = new Set(result.results.filter((r) => !r.ok).map((r) => r.id));
      selecting = selection.size > 0;
      if (saved.some((n) => n.id === editor.note?.id)) {
        await editor.open(null);
        documentKey++;
      }
      if (saved.length) notice = null;
      offerUndo(profile, saved);
      await refreshState(true);
      if (saved.length !== result.results.length)
        notify('batchResult', {
          count: saved.length,
          failed: result.results.length - saved.length,
        });
    });
  }
  async function undoTrash(): Promise<void> {
    const group = undoGroup;
    if (!group || group.profile !== state?.profile.id) {
      clearUndo();
      return;
    }
    clearUndo();
    const result = await call<BatchResult>({
      action: 'batch_set_deleted',
      profile_id: group.profile,
      deleted: false,
      items: group.notes.map((n) => ({ id: n.id, expected_generation: n.generation })),
    });
    await refreshState(true);
    const count = result.results.filter((r) => r.ok).length;
    notify('undoResult', { count, failed: result.results.length - count });
  }
  let pendingMarkdown: {
    profile: string;
    request: string;
    filename: string;
    bytes_base64: string;
  } | null = null;
  let markdownConfirmProfile: Profile | null = null;
  async function importMarkdown(confirmed = false): Promise<void> {
    const profile = state!.profile;
    if (!pendingMarkdown) {
      if (profile.kind === 'account' && !confirmed) {
        markdownConfirmProfile = profile;
        return;
      }
      const request = crypto.randomUUID();
      const picked = await native<{ filename: string; bytes_base64: string } | null>(
        'pick_markdown',
      );
      if (!picked) return;
      pendingMarkdown = { profile: profile.id, request, ...picked };
    }
    const pending = pendingMarkdown;
    if (pending.profile !== state!.profile.id) {
      pendingMarkdown = null;
      throw new LocalizedError('profileChanged');
    }
    await editor.finishLifecycle(async () => {
      let note: Note;
      try {
        note = await call<Note>({
          action: 'import_markdown',
          profile_id: pending.profile,
          request_id: pending.request,
          filename: pending.filename,
          bytes_base64: pending.bytes_base64,
        });
      } catch (error) {
        const code = (error as { detail?: { code?: string } })?.detail?.code;
        if (
          code &&
          [
            'IMPORT_INVALID_FILE',
            'IMPORT_INVALID_ENCODING',
            'IMPORT_EMPTY',
            'NOTE_TOO_LARGE',
            'PROFILE_CHANGED',
            'RECOVERY_REQUIRED',
          ].includes(code)
        )
          pendingMarkdown = null;
        throw error;
      }
      pendingMarkdown = null;
      await editor.open(note);
      documentKey++;
      trash = false;
      query = '';
      clearListActions();
      await refreshState(true);
      notify('importedMarkdown', {
        space: profileLabel(profile, t, language, preferences.timeZone),
      });
    });
  }
  let query = '';
  let trash = false;
  let mode: 'source' | 'preview' | 'split' = 'source';
  let booting = true;
  let busy = false;
  let lifecyclePending = false;
  let notice: Message | null = null;
  let failure: unknown = null;
  let settings = false;
  let settingsTab: 'account' | 'devices' | 'data' | 'appearance' | 'updates' = 'appearance';
  let authMode: 'login' | 'register' = 'login';
  let serverUrl = DEFAULT_SERVER_URL;
  let username = '';
  let password = '';
  let invite = '';
  let deviceName = createTranslator(resolveLanguage(preferences.language, systemLanguage))(
    'desktopDevice',
    { platform: /Windows/i.test(navigator.userAgent) ? 'Windows' : 'Linux' },
  );
  let devices: Device[] = [];
  let showImport = false;
  let importSource = 'local';
  let documentKey = 0;
  let activeMismatch = false;
  let latestState: AppState | null = null;
  let requestCounter = 0;
  let stateRequestCounter = 0;
  const processedRedirects = new Set<string>();
  let pendingRedirect: { profile: string; source: string; target: string } | null = null;
  let searchTimer: ReturnType<typeof setTimeout> | undefined;
  let poll: ReturnType<typeof setInterval> | undefined;
  let polling = false;
  let unlisteners: UnlistenFn[] = [];
  let editor = new EditorSession(
    call,
    () => {
      editor = editor;
    },
    () => {
      void refreshNotes().catch(() => {});
    },
  );

  const previewState = new PreviewState();
  $: previewSnapshot = previewState.capture({
    source: editor.content,
    profileId: state?.profile.id ?? 'local',
    documentKey,
    noteId: editor.note?.id ?? null,
  });
  let highlightTimer: ReturnType<typeof setTimeout> | undefined;
  let highlightRequest = 0;
  let highlighted: { source: string; result: CodeHighlights } | undefined;
  $: highlightSource = previewSnapshot.source.replace(/\r\n?/g, '\n');
  $: scheduleHighlight(highlightSource);
  $: codeHighlights = highlighted?.source === highlightSource ? highlighted.result : undefined;
  $: preview = renderMarkdown(
    previewSnapshot.source,
    !readOnly,
    previewSnapshot.version,
    language,
    codeHighlights,
  );

  function scheduleHighlight(source: string): void {
    clearTimeout(highlightTimer);
    const request = ++highlightRequest;
    if (!source.includes('```') && !source.includes('~~~')) return;
    highlightTimer = setTimeout(async () => {
      try {
        const result = await call<CodeHighlights>({ action: 'highlight_code', source });
        if (request === highlightRequest) highlighted = { source, result };
      } catch {
        // Markdown remains available if optional coloring fails.
      }
    }, 120);
  }
  $: syncLabel = state
    ? {
        local: t('localOnly'),
        pending: t('pending', { count: state.sync.pending }),
        syncing: t('syncing'),
        synced: t('synced'),
        offline: t('offline'),
        auth_required: t('authRequired'),
        recovery_required: t('recoveryRequired'),
        error: t('syncFailed'),
      }[state.sync.state]
    : t('opening');
  $: readOnly =
    (busy && !editor.composing) ||
    editor.inputLocked ||
    (lifecyclePending && !editor.composing) ||
    !!editor.note?.deleted ||
    !!state?.profile.read_only ||
    (activeMismatch && !editor.composing);
  $: title = [
    ...(editor.content
      .split('\n')
      .find((line) => line.trim())
      ?.replace(/^\s*#{1,6}\s+/, '') ?? t('untitled')),
  ]
    .slice(0, 80)
    .join('');

  function time(value: string | null, locale = language, zone = preferences.timeZone): string {
    return formatTime(value, locale, zone, t('noRecord'));
  }
  function excerpt(note: Note): string {
    return (
      note.content
        .split('\n')
        .filter((line) => line.trim())
        .slice(1)
        .join(' ')
        .slice(0, 90) || ''
    );
  }
  function closeMenus(except?: Node, restoreFocus = false): void {
    document.querySelectorAll<HTMLDetailsElement>('details[data-menu][open]').forEach((menu) => {
      if (!except || !menu.contains(except)) {
        const focused = menu.contains(document.activeElement);
        menu.open = false;
        if (restoreFocus && focused) menu.querySelector('summary')?.focus();
      }
    });
  }
  function menuAction(event: MouseEvent, work: () => void | Promise<void>): void {
    const menu = (event.currentTarget as HTMLElement).closest('details');
    if (menu) {
      menu.open = false;
      menu.querySelector('summary')?.focus();
    }
    void run(async () => {
      await work();
    });
  }
  $: saveLabel = t(
    editor.error
      ? 'saveFailed'
      : editor.saving || editor.unsaved || editor.composing
        ? 'saving'
        : editor.note
          ? 'saved'
          : 'blankDraft',
  );
  $: saveShortLabel = t(
    editor.error
      ? 'saveFailed'
      : editor.saving || editor.unsaved || editor.composing
        ? 'saving'
        : editor.note
          ? 'savedShort'
          : 'draftShort',
  );

  function notify(key: MessageKey, args?: MessageArgs): void {
    notice = { key, args };
    failure = null;
  }

  async function run(work: () => Promise<void>): Promise<void> {
    if (busy || lifecyclePending) return;
    busy = true;
    failure = null;
    try {
      await work();
    } catch (error) {
      failure = error;
    } finally {
      busy = false;
    }
  }
  async function refreshNotes(): Promise<void> {
    if (!state) return;
    const profileId = state.profile.id;
    const request = ++requestCounter;
    const next = await call<Note[]>({
      action: 'list',
      profile_id: profileId,
      query,
      deleted: trash,
    });
    if (request !== requestCounter || state.profile.id !== profileId) return;
    notes = next;
    selection = new Set([...selection].filter((id) => next.some((n) => n.id === id)));
    if (editor.note) {
      const selected = notes.find((note) => note.id === editor.note?.id);
      if (selected) editor.acceptRemote(selected);
      else {
        const current = await call<Note>({
          action: 'get',
          profile_id: profileId,
          id: editor.note.id,
        });
        if (state.profile.id === profileId) editor.acceptRemote(current);
      }
    }
  }
  async function refreshState(force = false): Promise<void> {
    const request = ++stateRequestCounter;
    const next = await call<AppState>({ action: 'state' });
    if (request !== stateRequestCounter) return;
    if (state && next.profile.id !== state.profile.id) {
      if (editor.unsaved || editor.saving || editor.composing || editor.error) {
        latestState = next;
        activeMismatch = true;
        failure = new LocalizedError('profileChanged');
        return;
      }
      await editor.open(null);
      documentKey++;
      clearListActions();
      clearUndo();
      pendingMarkdown = null;
      trash = false;
      query = '';
    }
    const changed =
      force || !state || next.profile.id !== state.profile.id || next.event_seq !== state.event_seq;
    if (state?.profile.id !== next.profile.id) {
      clearListActions();
      clearUndo();
      pendingMarkdown = null;
    }
    state = next;
    editor.profileId = next.profile.id;
    activeMismatch = false;
    for (const [source, target] of Object.entries(next.redirects ?? {})) {
      const key = `${next.profile.id}:${source}:${target}`;
      if (processedRedirects.has(key)) continue;
      processedRedirects.add(key);
      if (editor.note?.id === source)
        pendingRedirect = { profile: next.profile.id, source, target };
    }
    if (pendingRedirect) {
      const redirect = pendingRedirect;
      if (redirect.profile !== next.profile.id || editor.note?.id !== redirect.source)
        pendingRedirect = null;
      else if (!editor.unsaved && !editor.saving && !editor.composing && !editor.error) {
        const target = await call<Note>({
          action: 'get',
          profile_id: redirect.profile,
          id: redirect.target,
        });
        // Input typed while this read was in flight is kept; retry migration on the next poll.
        if (
          !editor.unsaved &&
          !editor.saving &&
          !editor.composing &&
          !editor.error &&
          editor.note?.id === redirect.source
        ) {
          await editor.open(target);
          documentKey++;
          pendingRedirect = null;
          notify('conflictOpened');
        }
      }
    }
    if (changed) await refreshNotes();
  }
  async function selectNote(note: Note): Promise<void> {
    pendingRedirect = null;
    await editor.flush();
    const fresh = await call<Note>({ action: 'get', profile_id: state!.profile.id, id: note.id });
    await editor.open(fresh);
    documentKey++;
    notice = null;
  }
  async function newNote(): Promise<void> {
    clearListActions();
    if (state?.profile.read_only) throw new LocalizedError('recoveryReadOnlyError');
    await editor.open(null);
    documentKey++;
    trash = false;
    query = '';
    await refreshNotes();
    notice = null;
  }
  async function changeSection(deleted: boolean): Promise<void> {
    setListVisible(true);
    await editor.flush();
    clearListActions();
    trash = deleted;
    query = '';
    await editor.open(null);
    documentKey++;
    await refreshNotes();
  }
  async function switchProfile(id: string): Promise<void> {
    setListVisible(true);
    if (state?.profile.id === id) return;
    await editor.flush();
    stateRequestCounter++;
    await call({ action: 'switch', profile_id: id });
    await editor.open(null);
    documentKey++;
    query = '';
    trash = false;
    clearListActions();
    clearUndo();
    pendingMarkdown = null;
    state = null;
    await refreshState(true);
    notify('profileSwitched');
  }
  async function authenticate(): Promise<void> {
    let url: URL;
    try {
      url = new URL(serverUrl);
    } catch {
      throw new LocalizedError('invalidServer');
    }
    if (
      (url.protocol !== 'https:' && !(runtime?.debug_http && url.protocol === 'http:')) ||
      url.username ||
      url.password ||
      url.search ||
      url.hash
    )
      throw new LocalizedError('invalidServer');
    if (!/^[a-z0-9][a-z0-9_-]{2,31}$/.test(username)) throw new LocalizedError('invalidUsername');
    if ([...password].length < 12 || [...password].length > 128)
      throw new LocalizedError('invalidPassword');
    if (!deviceName.trim() || [...deviceName].length > 128)
      throw new LocalizedError('invalidDevice');
    if (authMode === 'register' && !invite.trim()) throw new LocalizedError('missingInvite');
    await editor.flush();
    stateRequestCounter++;
    try {
      const result = await call<{ import_count: number }>({
        action: authMode,
        server_url: serverUrl,
        username,
        password,
        invite_code: invite,
        device_name: deviceName,
      });
      await editor.open(null);
      documentKey++;
      clearListActions();
      clearUndo();
      pendingMarkdown = null;
      state = null;
      query = '';
      trash = false;
      await refreshState(true);
      settings = false;
      showImport = result.import_count > 0;
      notify(authMode === 'register' ? 'registered' : 'signedIn');
    } finally {
      password = '';
      invite = '';
    }
  }
  async function importNotes(source = 'local'): Promise<void> {
    await editor.flush();
    const result = await call<{ imported: number }>({
      action: 'import',
      profile_id: state!.profile.id,
      source_profile_id: source,
    });
    showImport = false;
    await refreshState(true);
    notify('imported', { count: result.imported });
  }
  async function logout(): Promise<void> {
    await editor.flush();
    stateRequestCounter++;
    const result = await call<{ remote_revoked: boolean }>({ action: 'logout' });
    await editor.open(null);
    documentKey++;
    clearListActions();
    clearUndo();
    pendingMarkdown = null;
    state = null;
    query = '';
    trash = false;
    devices = [];
    await refreshState(true);
    notify(result.remote_revoked ? 'loggedOut' : 'loggedOutOffline');
  }
  async function synchronize(): Promise<void> {
    await editor.flush();
    await call({ action: 'sync' });
    await refreshState(true);
  }
  async function setDeleted(deleted: boolean): Promise<void> {
    await editor.flush();
    if (!editor.note) return;
    if (deleted) {
      await batchTrash([editor.note]);
      return;
    }
    const result = await call<Note>({
      action: 'set_deleted',
      profile_id: state!.profile.id,
      id: editor.note.id,
      expected_generation: editor.note.generation,
      deleted,
    });
    await editor.open(result);
    await refreshState(true);
    notify(deleted ? 'movedToTrash' : 'restored');
  }
  async function handlePreview(event: MouseEvent): Promise<void> {
    const target = event.target as HTMLElement;
    const link = target.closest<HTMLAnchorElement>('a[href]');
    if (link) {
      event.preventDefault();
      await run(async () => {
        await native('open_external', { url: link.href });
      });
      return;
    }
    const task = target.closest<HTMLButtonElement>('button[data-task-line]');
    if (!task || readOnly) return;
    event.preventDefault();
    const line = Number(task.dataset.taskLine);
    const snapshot = previewSnapshot;
    const buttonVersion = task.dataset.previewVersion;
    await run(async () => {
      await editor.flush();
      if (!editor.note) return;
      if (
        !taskSnapshotMatches(snapshot, buttonVersion, {
          source: editor.content,
          profileId: state!.profile.id,
          documentKey,
          noteId: editor.note.id,
        })
      ) {
        notify('previewChanged');
        return;
      }
      const result = await call<Note>({
        action: 'toggle_task',
        profile_id: state!.profile.id,
        id: editor.note.id,
        expected_generation: editor.note.generation,
        line,
      });
      editor.acceptRemote(result);
      await refreshState(true);
    });
  }
  async function exportNotes(): Promise<void> {
    await editor.flush();
    const path = await native<string | null>('export_notes', {
      profileId: state!.profile.id,
      includeDeleted: trash,
      deletedOnly: trash,
    });
    if (path) notify('exported', { path });
  }
  async function rescue(andSwitch = false): Promise<void> {
    const path = await native<string | null>('rescue_text', { content: editor.content });
    if (!path) return;
    if (andSwitch && latestState) {
      editor.dispose();
      editor = new EditorSession(
        call,
        () => {
          editor = editor;
        },
        () => {
          void refreshNotes().catch(() => {});
        },
      );
      state = latestState;
      latestState = null;
      editor.profileId = state.profile.id;
      activeMismatch = false;
      documentKey++;
      await refreshState(true);
    }
    notify('rescued', { path });
  }
  async function copyBuffer(): Promise<void> {
    await navigator.clipboard.writeText(editor.content);
    notify('copied');
  }
  async function openSettings(tab: typeof settingsTab = 'appearance'): Promise<void> {
    settingsTab = tab;
    settings = true;
    if (tab === 'devices' && state?.profile.kind === 'account')
      devices = await call<Device[]>({ action: 'devices' });
  }
  async function installUpdate(): Promise<void> {
    if (busy || lifecyclePending) throw new Error('UPDATE_BUSY');
    busy = true;
    lifecyclePending = true;
    try {
      await editor.finishLifecycle(() => native('install_update'));
    } finally {
      busy = false;
      lifecyclePending = false;
    }
  }
  async function revoke(id: string): Promise<void> {
    await call({ action: 'revoke_device', id });
    devices = await call<Device[]>({ action: 'devices' });
    await refreshState(true);
    notify('revokedNotice');
  }
  function search(): void {
    clearListActions();
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      void refreshNotes().catch((error) => {
        failure = error;
      });
    }, 120);
  }
  function foreground(): void {
    if (lifecyclePending) return;
    void call({
      action: 'foreground',
      active: document.visibilityState === 'visible',
    }).catch(() => {});
  }
  function blur(): void {
    closeMenus();
    void editor.flush().catch((error) => {
      failure = error;
    });
    foreground();
  }
  function previewEvents(node: HTMLElement) {
    const click = (event: MouseEvent) => {
      void handlePreview(event);
    };
    node.addEventListener('click', click);
    return {
      destroy() {
        node.removeEventListener('click', click);
      },
    };
  }
  function modal(node: HTMLElement, close: () => void) {
    const previous = document.activeElement as HTMLElement | null;
    const focusable = () => [
      ...node.querySelectorAll<HTMLElement>(
        'button:not(:disabled), input:not(:disabled), select:not(:disabled), [tabindex="0"]',
      ),
    ];
    queueMicrotask(() => (focusable()[0] ?? node).focus());
    const keydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) {
        event.preventDefault();
        close();
      }
      if (event.key === 'Tab') {
        const items = focusable();
        const first = items[0];
        const last = items[items.length - 1];
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault();
          last?.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first?.focus();
        }
      }
    };
    node.addEventListener('keydown', keydown);
    return {
      destroy() {
        node.removeEventListener('keydown', keydown);
        previous?.focus();
      },
    };
  }

  onMount(() => {
    const changedTheme = (event: MediaQueryListEvent) => {
      browserDark = event.matches;
    };
    const changedLanguage = () => {
      systemLanguage = navigator.language;
    };
    void refreshSystemAppearance();
    const appearancePoll = setInterval(() => {
      if (preferences.theme === 'system') void refreshSystemAppearance();
    }, 2000);
    colorScheme.addEventListener('change', changedTheme);
    window.addEventListener('languagechange', changedLanguage);
    const cleanup = () => {
      clearInterval(appearancePoll);
      colorScheme.removeEventListener('change', changedTheme);
      window.removeEventListener('languagechange', changedLanguage);
    };
    void (async () => {
      try {
        unlisteners.push(
          await listen<{ id: number }>('memo-lifecycle', async (event) => {
            if (lifecyclePending) return;
            lifecyclePending = true;
            try {
              await editor.finishLifecycle(() =>
                native('finish_lifecycle', { id: event.payload.id, saved: true }),
              );
            } catch (error) {
              failure = error;
              await native('finish_lifecycle', { id: event.payload.id, saved: false });
            } finally {
              lifecyclePending = false;
            }
          }),
        );
        unlisteners.push(
          await listen('memo-new', () => {
            void run(newNote);
          }),
        );
        unlisteners.push(
          await listen('memo-sync', () => {
            void run(synchronize);
          }),
        );
        await native('frontend_ready');
        runtime = await native<RuntimeInfo>('runtime_info');
        await refreshState(true);
        foreground();
        poll = setInterval(() => {
          if (polling || busy || lifecyclePending) return;
          polling = true;
          void refreshState()
            .catch((error) => {
              failure = error;
            })
            .finally(() => {
              polling = false;
            });
        }, 1000);
      } catch (error) {
        failure = error;
      } finally {
        booting = false;
      }
    })();
    return cleanup;
  });
  onDestroy(() => {
    ++highlightRequest;
    clearTimeout(highlightTimer);
    clearInterval(poll);
    clearTimeout(searchTimer);
    unlisteners.forEach((stop) => stop());
    editor.dispose();
  });
</script>

<svelte:window
  on:pointerdown={(event) => closeMenus(event.target instanceof Node ? event.target : undefined)}
  on:focusin={(event) => closeMenus(event.target instanceof Node ? event.target : undefined)}
  on:blur={blur}
  on:focus={foreground}
  on:online={() => {
    void call({ action: 'sync' }).catch(() => {});
  }}
  on:keydown={(event) => {
    if (event.key === 'Escape') {
      closeMenus(undefined, true);
      if (selecting && !busy) clearListActions();
    }
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'n') {
      event.preventDefault();
      void run(newNote);
    }
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
      event.preventDefault();
      void run(() => editor.flush());
    }
  }}
/>
<svelte:document
  on:visibilitychange={() => {
    if (document.visibilityState === 'hidden') blur();
    else foreground();
  }}
/>

<main class="app-shell" class:list-collapsed={!listVisible} aria-busy={booting}>
  <nav class="nav-rail" aria-label={t('navigation')}>
    <button
      class="rail-button primary-icon"
      aria-label={t('newNote')}
      title={t('newNote') + ' · Ctrl N'}
      disabled={busy || booting || !!state?.profile.read_only || activeMismatch}
      on:click={() => run(newNote)}><Icon name="plus" size={24} /></button
    >
    <div class="rail-group">
      <button
        class="rail-button"
        class:active={!trash}
        aria-label={t('allNotes')}
        title={t('allNotes')}
        aria-pressed={!trash}
        on:click={() => run(() => changeSection(false))}><Icon name="file-text" /></button
      >
      <button
        class="rail-button"
        class:active={trash}
        aria-label={t('trash')}
        title={t('trash')}
        aria-pressed={trash}
        on:click={() => run(() => changeSection(true))}><Icon name="trash-2" /></button
      >
    </div>
    <div class="rail-bottom">
      <button
        class="rail-button"
        aria-label={t('settings')}
        title={t('settings')}
        on:click={() => run(() => openSettings())}><Icon name="settings" /></button
      >
    </div>
  </nav>
  <section
    id="note-list"
    class="note-column"
    class:concealed={!listVisible}
    aria-label={t('noteList')}
  >
    <header class="list-header">
      <div class="brand-row">
        <strong class="brand">DJHX Memo</strong>
        <details class="action-menu" data-menu>
          <!-- svelte-ignore a11y_no_redundant_roles (WebKitGTK exposes an unknown AT-SPI role without this explicit role.) -->
          <summary
            role="button"
            class="icon-button"
            aria-label={t('moreActions')}
            title={t('moreActions')}><Icon name="ellipsis" /></summary
          >
          <div class="menu-panel">
            <button
              disabled={!state || busy || state.profile.read_only}
              on:click={(event) => menuAction(event, importMarkdown)}
              ><Icon name="download" />{t('importMarkdown')}</button
            >
            {#if pendingMarkdown}<button
                on:click={() => {
                  pendingMarkdown = null;
                }}>{t('cancel')} · {t('importMarkdown')}</button
              >{/if}
            {#if !trash}<button
                disabled={busy || !!state?.profile.read_only}
                on:click={() => {
                  closeMenus();
                  selecting = !selecting;
                  selection = new Set();
                }}>{t('selectNotes')}</button
              >{/if}
            <button disabled={!state || busy} on:click={(event) => menuAction(event, exportNotes)}
              ><Icon name="download" />{t(trash ? 'exportTrash' : 'exportNotes')}</button
            >
            <button on:click={(event) => menuAction(event, () => openSettings('account'))}
              ><Icon name="user" />{t('accountTab')}</button
            >
            <button on:click={(event) => menuAction(event, () => openSettings())}
              ><Icon name="settings" />{t('settings')}</button
            >
          </div>
        </details>
      </div>
      <div
        class="list-heading"
        class:selecting
        role="toolbar"
        aria-label={t(selecting ? 'selectNotes' : 'noteList')}
      >
        {#if selecting}
          <button
            class="icon-button selection-exit"
            disabled={busy}
            aria-label={t('cancel')}
            title={t('cancel')}
            on:click={clearListActions}><Icon name="x" size={18} /></button
          >
          <strong class="selection-count">{t('selectedCount', { count: selection.size })}</strong>
          <button
            class="selection-all"
            class:checked={selection.size === notes.length && notes.length > 0}
            disabled={busy || !notes.length}
            title={t('selectAll')}
            aria-label={t('selectAll')}
            aria-pressed={selection.size === notes.length && notes.length > 0}
            on:click={() => {
              selection =
                selection.size === notes.length ? new Set() : new Set(notes.map((n) => n.id));
            }}
          >
            <span class="selection-check" aria-hidden="true"
              >{#if selection.size === notes.length && notes.length > 0}<Icon
                  name="check"
                  size={12}
                />{/if}</span
            >{t('selectAllShort')}
          </button>
          <button
            class="icon-button selection-trash"
            disabled={busy || !selection.size}
            aria-label={t('moveToTrash')}
            title={t('moveToTrash')}
            on:click={() => run(() => batchTrash())}><Icon name="trash-2" size={18} /></button
          >
        {:else}
          <h1>{t(trash ? 'trash' : 'allNotes')}</h1>
          <span>{t(query ? 'matches' : 'records', { count: notes.length })}</span>
          {#if !trash}
            <button
              class="list-select-button"
              disabled={busy || !state || state.profile.read_only}
              aria-label={t('selectNotes')}
              on:click={() => {
                selecting = true;
                selection = new Set();
              }}
            >
              <span class="selection-check" aria-hidden="true"><Icon name="check" size={12} /></span
              >{t('selectShort')}
            </button>
          {/if}
          <button
            class="icon-button"
            aria-label={t('refreshList')}
            title={t('refresh')}
            disabled={busy}
            on:click={() => run(() => refreshState(true))}
            ><Icon name="refresh-cw" size={16} /></button
          >
        {/if}
      </div>
      <label class="search"
        ><Icon name="search" size={20} /><input
          aria-label={t('searchCurrent')}
          bind:value={query}
          disabled={busy}
          on:input={search}
          placeholder={t('searchNotes')}
        /></label
      >
    </header>
    <div class="notes-scroll">
      {#each notes as note (note.id)}
        <button
          class="note-card"
          class:selected={selecting ? selection.has(note.id) : editor.note?.id === note.id}
          aria-pressed={selecting ? selection.has(note.id) : undefined}
          on:click={() => (selecting ? toggleSelection(note.id) : run(() => selectNote(note)))}
          disabled={busy}
        >
          {#if selecting}<span
              class="selection-check note-check"
              class:checked={selection.has(note.id)}
              aria-hidden="true"
              >{#if selection.has(note.id)}<Icon name="check" size={13} />{/if}</span
            >{/if}
          <div class="note-card-body">
            <div class="note-card-title">{note.content.trim() ? note.title : t('untitled')}</div>
            {#if note.purge_state === 'pending'}<p>
                {t('purgePending')}
              </p>{:else if note.purge_state === 'rejected'}<p>{t('purgeRejected')}</p>{/if}
            {#if excerpt(note)}<p>{excerpt(note)}</p>{/if}
            <div class="note-meta">
              <time>{time(note.updated_at, language, preferences.timeZone)}</time>
              {#if note.conflict}<span class="conflict-chip">{t('conflictCopy')}</span
                >{:else if note.sync_state === 'pending'}<span
                  class="pending-dot"
                  title={t('pendingShort')}
                ></span>{/if}
            </div>
          </div>
        </button>
      {:else}
        <div class="list-empty">
          <Icon name={query ? 'search' : trash ? 'trash-2' : 'file-text'} size={24} /><strong
            >{t(query ? 'noSearchResults' : trash ? 'emptyTrash' : 'noNotesYet')}</strong
          >
        </div>
      {/each}
    </div>
    <footer class="list-footer">
      <button
        class="profile-button"
        aria-label={t('switchSpace')}
        title={state
          ? profileLabel(state.profile, t, language, preferences.timeZone)
          : t('currentSpace')}
        on:click={() => run(() => openSettings('account'))}
      >
        <Icon name={state?.profile.kind === 'account' ? 'cloud' : 'file-text'} size={18} />
        <span
          >{state?.profile.kind === 'account'
            ? state.profile.username
            : t(state?.profile.kind === 'recovery' ? 'recoverySpace' : 'localNotes')}</span
        ><Icon name="chevron-down" size={16} />
      </button>
    </footer>
  </section>
  <section class="workspace" aria-label={t('noteEditor')}>
    <header class="workspace-header">
      <button
        class="icon-button"
        aria-label={t(listVisible ? 'hideNoteList' : 'showNoteList')}
        title={t(listVisible ? 'hideNoteList' : 'showNoteList')}
        aria-expanded={listVisible}
        aria-controls="note-list"
        on:click={() => setListVisible(!listVisible)}><Icon name="panel-left" /></button
      >
      {#if !listVisible && state}<button
          class="space-chip"
          title={profileLabel(state.profile, t, language, preferences.timeZone)}
          on:click={() => run(() => openSettings('account'))}
          >{profileLabel(state.profile, t, language, preferences.timeZone)}</button
        >{/if}
      <strong class="document-title">{editor.note ? title : t('newDraft')}</strong>
      <div class="editor-actions">
        <div class="view-switch" aria-label={t('editorMode')}>
          <button
            class:chosen={mode === 'source'}
            aria-label={t('source')}
            title={t('source')}
            aria-pressed={mode === 'source'}
            on:click={() => {
              mode = 'source';
            }}><Icon name="pencil" /></button
          >
          <button
            class:chosen={mode === 'preview'}
            aria-label={t('preview')}
            title={t('preview')}
            aria-pressed={mode === 'preview'}
            on:click={() => {
              mode = 'preview';
            }}><Icon name="eye" /></button
          >
          <button
            class:chosen={mode === 'split'}
            aria-label={t('split')}
            title={t('split')}
            aria-pressed={mode === 'split'}
            on:click={() => {
              mode = 'split';
            }}><Icon name="columns-2" /></button
          >
        </div>
        <details class="action-menu" data-menu>
          <!-- svelte-ignore a11y_no_redundant_roles (WebKitGTK exposes an unknown AT-SPI role without this explicit role.) -->
          <summary
            role="button"
            class="icon-button"
            aria-label={t('noteActions')}
            title={t('noteActions')}><Icon name="ellipsis" /></summary
          >
          <div class="menu-panel">
            {#if editor.note?.deleted && editor.note.purge_state !== 'purged'}<button
                disabled={busy || state?.profile.read_only || editor.note.purge_state === 'pending'}
                on:click={(event) => menuAction(event, () => setDeleted(false))}
                ><Icon name="rotate-ccw" />{t('restoreNote')}</button
              >
              <button
                class="danger"
                disabled={busy || state?.profile.read_only || editor.note.purge_state === 'pending'}
                on:click={(event) =>
                  menuAction(event, () => {
                    purgeConfirmation = {
                      id: editor.note!.id,
                      profile: state!.profile.id,
                      generation: editor.note!.generation,
                    };
                  })}><Icon name="trash-2" />{t('purgeNote')}</button
              >
            {:else if !editor.note?.deleted}<button
                class="danger"
                disabled={busy || !editor.note || state?.profile.read_only}
                on:click={(event) => menuAction(event, () => setDeleted(true))}
                ><Icon name="trash-2" />{t('moveToTrash')}</button
              >{/if}
            <button
              disabled={busy || !editor.content}
              on:click={(event) => menuAction(event, copyBuffer)}
              ><Icon name="copy" />{t('copyText')}</button
            >
            <button
              disabled={busy || !editor.content}
              on:click={(event) => menuAction(event, () => rescue())}
              ><Icon name="download" />{t('exportCurrent')}</button
            >
            {#if !listVisible}<button
                disabled={!state || busy}
                on:click={(event) => menuAction(event, exportNotes)}
                ><Icon name="download" />{t(trash ? 'exportTrash' : 'exportNotes')}</button
              >{/if}
          </div>
        </details>
      </div>
    </header>
    {#if runtime && !runtime.tray_available}<div
        class="info-strip compact-notice"
        title={t('trayUnavailable')}
      >
        <Icon name="info" size={16} /><span>{t('closeToQuitHelp')}</span>
      </div>{/if}
    {#if state && !state.credential_persistent && state.profile.kind === 'account'}<div
        class="info-strip warning-strip"
      >
        {t('keyringUnavailable')}
      </div>{/if}
    {#if state?.sync.state === 'auth_required'}<div class="info-strip warning-strip">
        {t('signInAgainHelp')}<button on:click={() => run(() => openSettings('account'))}
          >{t('signInAgain')}</button
        >
      </div>{/if}
    {#if state?.sync.state === 'recovery_required'}<div class="info-strip warning-strip">
        {t('recoveryHelp')}<button on:click={() => run(() => openSettings('data'))}
          >{t('view')}</button
        >
      </div>{/if}
    {#if state?.profile.read_only}<div class="info-strip">
        {t('readOnlyHelp')}
      </div>{/if}
    {#if failure}<div class="message error-message" role="alert">
        <span>{errorText(failure, language)}</span><button
          aria-label={t('dismiss')}
          title={t('dismiss')}
          on:click={() => {
            failure = null;
          }}><Icon name="x" size={20} /></button
        >
      </div>{:else if notice}<div class="message success-message" role="status">
        <span>{notice ? t(notice.key, notice.args) : ''}</span><button
          aria-label={t('dismiss')}
          title={t('dismiss')}
          on:click={() => {
            notice = null;
          }}><Icon name="x" size={20} /></button
        >
      </div>{/if}
    {#if editor.error || activeMismatch}<div class="save-rescue">
        <strong>{t('inputPreserved')}</strong><span
          >{editor.generationConflict ? t('generationHelp') : t('saveHelp')}</span
        >
        <div>
          {#if !activeMismatch}<button
              on:click={() =>
                run(() => (editor.generationConflict ? editor.saveAsCopy() : editor.flush()))}
              >{editor.generationConflict ? t('saveAsCopy') : t('retrySave')}</button
            >{/if}<button on:click={() => run(copyBuffer)}>{t('copyText')}</button><button
            on:click={() => run(() => rescue(activeMismatch))}
            >{activeMismatch ? t('rescueSwitch') : t('exportCurrent')}</button
          >
        </div>
      </div>{/if}
    {#if editor.note?.conflict}<div class="conflict-banner">
        <strong>{t('conflictCopy')}</strong><span
          >{editor.note.conflict.reason === 'stale_delete' ? t('staleDelete') : t('conflictHelp')}
          {t('conflictSource', {
            name: editor.note.conflict.source_device_name,
            time: time(editor.note.conflict.detected_at, language, preferences.timeZone),
          })}</span
        ><small>{t('originalNote', { id: editor.note.conflict.source_note_id })}</small>
      </div>{/if}
    {#if editor.note?.purge_state === 'purged'}<div class="info-strip warning-strip">
        {t('notePurged')}
      </div>
    {:else if editor.note?.purge_state === 'pending'}<div class="info-strip warning-strip">
        {t('purgePending')}
      </div>
    {:else if editor.note?.purge_state === 'rejected'}<div class="info-strip warning-strip">
        {t('purgeRejected')}
      </div>
    {/if}
    {#if editor.note?.deleted && !['pending', 'purged'].includes(editor.note.purge_state ?? '')}<div
        class="info-strip warning-strip"
      >
        {t('deletedHelp')}<button
          disabled={busy || state?.profile.read_only}
          on:click={() => run(() => setDeleted(false))}>{t('restoreNote')}</button
        >
      </div>{/if}
    {#if booting}<div class="boot-state">{t('openingLocal')}</div>{:else if !state}<div
        class="boot-state"
      >
        {t('openFailed')}<button on:click={() => run(() => refreshState(true))}>{t('retry')}</button
        >
      </div>{:else}<div class="editor-body" class:split={mode === 'split'}>
        <div
          class="source-pane"
          class:concealed={mode === 'preview'}
          aria-hidden={mode === 'preview'}
        >
          {#key documentKey}<Editor
              value={editor.content}
              highlights={codeHighlights}
              composing={editor.composing}
              {language}
              readonly={readOnly}
              onEdit={(text) => editor.edit(text)}
              onComposition={(active) => editor.setComposing(active)}
              onBlur={() => {
                void editor.flush().catch((error) => {
                  failure = error;
                });
              }}
            />{/key}
        </div>
        <div
          class="preview-pane"
          class:concealed={mode === 'source'}
          aria-hidden={mode === 'source'}
        >
          <article class="markdown" use:previewEvents role="document" aria-label={t('safePreview')}>
            {#if editor.content}{@html preview}{:else}<div class="preview-empty">
                <Icon name="file-text" size={24} />
                <h2>{t('emptyNoteTitle')}</h2>
              </div>{/if}
          </article>
        </div>
      </div>{/if}
    <footer class="editor-footer">
      <details class="status-popover" data-menu>
        <!-- svelte-ignore a11y_no_redundant_roles (WebKitGTK exposes an unknown AT-SPI role without this explicit role.) -->
        <summary
          role="button"
          class="status-button"
          class:error={!!editor.error}
          aria-label={t('statusDetails')}
          title={t('statusDetails')}
        >
          <Icon
            name={editor.error
              ? 'circle-alert'
              : editor.saving || editor.unsaved || editor.composing
                ? 'refresh-cw'
                : editor.note
                  ? 'check'
                  : 'file-text'}
            size={16}
          />
          <span>{saveShortLabel}</span><span class="status-divider"></span>
          <Icon
            name={state?.profile.kind !== 'account'
              ? 'file-text'
              : state.sync.state === 'synced'
                ? 'cloud-check'
                : state.sync.state === 'offline' || state.sync.state === 'error'
                  ? 'cloud-off'
                  : state.sync.state === 'syncing'
                    ? 'refresh-cw'
                    : 'cloud'}
            size={16}
          />
          <span
            class:cloud-warning={state?.sync.state === 'error' ||
              state?.sync.state === 'auth_required' ||
              state?.sync.state === 'recovery_required'}>{syncLabel}</span
          ><Icon name="chevron-down" size={14} />
        </summary>
        <div class="status-panel">
          <h3>{t('statusDetails')}</h3>
          <dl class="status-details">
            <dt>{t('localStatus')}</dt>
            <dd>{saveLabel}</dd>
            <dt>{t('cloudStatus')}</dt>
            <dd>{syncLabel}</dd>
            <dt>{t('lastSync')}</dt>
            <dd>{time(state?.sync.last_success ?? null, language, preferences.timeZone)}</dd>
          </dl>
          {#if runtime && !runtime.tray_available}<p class="help-text">
              {t('trayUnavailable')}
            </p>{/if}
          {#if state?.sync.error}<p class="inline-error">
              {coreErrorText(state.sync.error, language)}
            </p>{/if}
          <button
            class="secondary-button"
            disabled={busy || state?.profile.kind !== 'account'}
            on:click={(event) => menuAction(event, synchronize)}
            ><Icon name="refresh-cw" size={16} />{t('syncNow')}</button
          >
        </div>
      </details>
      <span class="character-count"
        >{t('characters', { count: [...editor.content].length.toLocaleString(language) })}</span
      >
    </footer>
  </section>
</main>

{#if settings}
  <div class="modal-backdrop" role="presentation">
    <div
      class="settings-dialog"
      use:modal={() => {
        settings = false;
      }}
      role="dialog"
      aria-modal="true"
      aria-labelledby="settings-heading"
      tabindex="-1"
    >
      <header class="dialog-header">
        <div>
          <h2 id="settings-heading">{t('settings')}</h2>
        </div>
        <button
          class="icon-button settings-close"
          aria-label={t('closeSettings')}
          title={t('closeSettings')}
          disabled={busy}
          on:click={() => {
            settings = false;
          }}><Icon name="x" size={20} /></button
        >
      </header>
      <nav class="settings-tabs" aria-label={t('settingsSections')}>
        <button
          class:chosen={settingsTab === 'appearance'}
          on:click={() => run(() => openSettings('appearance'))}>{t('appearanceTab')}</button
        >
        <button
          class:chosen={settingsTab === 'account'}
          on:click={() => run(() => openSettings('account'))}>{t('accountTab')}</button
        >
        <button
          class:chosen={settingsTab === 'devices'}
          on:click={() => run(() => openSettings('devices'))}>{t('devicesTab')}</button
        >
        <button
          class:chosen={settingsTab === 'data'}
          on:click={() => run(() => openSettings('data'))}>{t('dataTab')}</button
        >
        <button
          class:chosen={settingsTab === 'updates'}
          on:click={() => run(() => openSettings('updates'))}>{t('updatesTab')}</button
        >
      </nav>
      <div class="dialog-content">
        {#if failure}<div class="inline-error" role="alert">
            {errorText(failure, language)}
          </div>{/if}
        {#if notice}<div class="inline-notice" role="status">
            {notice ? t(notice.key, notice.args) : ''}
          </div>{/if}
        {#if settingsTab === 'account'}
          <h3>{t('currentSpace')}</h3>
          <div class="current-profile">
            <span class="avatar"
              ><Icon
                name={state?.profile.kind === 'local' ? 'file-text' : 'cloud'}
                size={20}
              /></span
            >
            <div>
              <strong
                >{state
                  ? profileLabel(state.profile, t, language, preferences.timeZone)
                  : ''}</strong
              ><small
                >{state?.profile.kind === 'local'
                  ? t('localPrivate')
                  : state?.profile.server_url}</small
              >
            </div>
            {#if state?.profile.kind === 'account'}<button
                class="text-button"
                disabled={busy}
                on:click={() => run(logout)}>{t('logout')}</button
              >{/if}
          </div>
          <div class="profiles">
            {#each state?.profiles ?? [] as profile (profile.id)}<button
                class:profile-selected={state?.profile.id === profile.id}
                disabled={busy}
                on:click={() => run(() => switchProfile(profile.id))}
                ><Icon
                  name={profile.kind === 'local'
                    ? 'file-text'
                    : profile.kind === 'recovery'
                      ? 'rotate-ccw'
                      : 'cloud'}
                  size={20}
                />
                <div>
                  <strong>{profileLabel(profile, t, language, preferences.timeZone)}</strong><small
                    >{profile.kind === 'local'
                      ? t('localOnly')
                      : profile.kind === 'recovery'
                        ? t('readOnly')
                        : profile.server_url}</small
                  >
                </div>
                <Icon
                  name={state?.profile.id === profile.id ? 'check' : 'chevron-down'}
                  size={16}
                /></button
              >{/each}
          </div>
          <div class="auth-heading">
            <h3>{authMode === 'login' ? t('loginOther') : t('registerInvite')}</h3>
            <button
              class="text-button"
              on:click={() => {
                authMode = authMode === 'login' ? 'register' : 'login';
              }}>{authMode === 'login' ? t('registerLink') : t('loginLink')}</button
            >
          </div>
          <form novalidate on:submit|preventDefault={() => run(authenticate)}>
            <label class="field"
              >{t('serverAddress')}<input
                type="url"
                required
                bind:value={serverUrl}
                placeholder={DEFAULT_SERVER_URL}
                autocomplete="url"
              />{#if runtime?.debug_http}<small>{t('debugServerHelp')}</small>{/if}</label
            >
            <div class="field-row">
              <label class="field"
                >{t('username')}<input
                  required
                  bind:value={username}
                  minlength="3"
                  maxlength="32"
                  pattern={'[a-z0-9][a-z0-9_-]{2,31}'}
                  autocomplete="username"
                  placeholder="your-name"
                /></label
              ><label class="field"
                >{t('deviceName')}<input
                  required
                  bind:value={deviceName}
                  maxlength="128"
                  placeholder={t('devicePlaceholder')}
                /></label
              >
            </div>
            <label class="field"
              >{t('password')}<input
                type="password"
                required
                bind:value={password}
                minlength="12"
                maxlength="256"
                autocomplete={authMode === 'register' ? 'new-password' : 'current-password'}
                placeholder={t('passwordPlaceholder')}
              /></label
            >
            {#if authMode === 'register'}<label class="field"
                >{t('invite')}<input
                  required
                  bind:value={invite}
                  autocomplete="off"
                  placeholder={t('invitePlaceholder')}
                /></label
              >{/if}
            <p class="help-text">
              {t('accountHelp')}
            </p>
            <button class="primary-button auth-submit" disabled={busy}
              >{busy
                ? t('processing')
                : authMode === 'login'
                  ? t('loginSubmit')
                  : t('registerSubmit')}</button
            >
          </form>
        {:else if settingsTab === 'devices'}
          <h3>{t('devicesTab')}</h3>
          <p class="help-text">{t('devicesHelp')}</p>
          {#if state?.profile.kind !== 'account'}<div class="settings-empty">
              {t('devicesNeedAccount')}
            </div>{:else}<div class="device-list">
              {#each devices as device (device.id)}<div class="device-row">
                  <span class="device-symbol"><Icon name="monitor" size={20} /></span>
                  <div>
                    <strong
                      >{device.name}
                      {#if device.current}<em>{t('currentDevice')}</em>{/if}</strong
                    ><small
                      >{t(device.revoked_at ? 'revokedAt' : 'lastActive', {
                        time: time(
                          device.revoked_at ?? device.last_seen_at,
                          language,
                          preferences.timeZone,
                        ),
                      })}</small
                    ><small
                      >{t('expiresAt', {
                        time: time(device.expires_at, language, preferences.timeZone),
                      })}</small
                    >
                  </div>
                  <button
                    class="text-button danger"
                    disabled={busy || !!device.revoked_at}
                    on:click={() => run(() => revoke(device.id))}
                    >{device.revoked_at ? t('revoked') : t('revoke')}</button
                  >
                </div>{:else}<div class="settings-empty">
                  {t('noDevices')}<button on:click={() => run(() => openSettings('devices'))}
                    >{t('reload')}</button
                  >
                </div>{/each}
            </div>{/if}
        {:else if settingsTab === 'updates'}
          <Updates {language} timeZone={preferences.timeZone} install={installUpdate} />
        {:else if settingsTab === 'appearance'}
          <label class="field"
            >{t('language')}<select bind:value={preferences.language} on:change={updatePreferences}>
              <option value="system">{t('followSystem')}</option><option value="zh-CN"
                >简体中文</option
              ><option value="en">English</option>
            </select></label
          >
          <label class="field"
            >{t('appearance')}<select bind:value={preferences.theme} on:change={updatePreferences}>
              <option value="system">{t('followSystem')}</option><option value="light"
                >{t('light')}</option
              ><option value="dark">{t('dark')}</option>
            </select></label
          >
          <label class="field"
            >{t('timeZone')}<select bind:value={preferences.timeZone} on:change={updatePreferences}>
              <option value="Asia/Shanghai">{t('shanghai')}</option><option value="UTC"
                >{t('utc')}</option
              ><option value="system">{t('followSystem')}</option>
            </select></label
          >
        {:else}
          <h3>{t('exportSpace')}</h3>
          <p class="help-text">
            {t('exportHelp')}
          </p>
          <button class="secondary-button" disabled={busy} on:click={() => run(exportNotes)}
            ><Icon name="download" size={18} />{t(trash ? 'exportDeleted' : 'exportActive')}</button
          >
          <div class="settings-separator"></div>
          <h3>{t('copySpace')}</h3>
          <p class="help-text">
            {t('copySpaceHelp')}
          </p>
          {#if state?.profile.kind === 'account'}<label class="field"
              >{t('sourceSpace')}<select bind:value={importSource}
                >{#each state.profiles.filter((p) => p.kind === 'local' || p.kind === 'recovery') as profile (profile.id)}<option
                    value={profile.id}
                    >{profileLabel(profile, t, language, preferences.timeZone)}</option
                  >{/each}</select
              ></label
            >
            <p class="import-target">
              {t('targetSpace', {
                name: profileLabel(state.profile, t, language, preferences.timeZone),
                server: state.profile.server_url ?? '',
              })}
            </p>
            <button
              class="secondary-button"
              disabled={busy}
              on:click={() => run(() => importNotes(importSource))}
              >{t('copyToAccount')}{importSource === 'local'
                ? t('noteCountSuffix', { count: state.local_import_count })
                : ''}</button
            >{:else}<div class="settings-empty">{t('copyNeedAccount')}</div>{/if}
          <div class="settings-separator"></div>
          <h3>{t('syncDetails')}</h3>
          <dl class="status-details">
            <dt>{t('currentStatus')}</dt>
            <dd>{syncLabel}</dd>
            <dt>{t('lastSync')}</dt>
            <dd>{time(state?.sync.last_success ?? null, language, preferences.timeZone)}</dd>
            <dt>{t('closeBehavior')}</dt>
            <dd>{runtime?.tray_available ? t('hideAfterSave') : t('quitAfterSave')}</dd>
            <dt>{t('clientVersion')}</dt>
            <dd>{runtime?.version}</dd>
          </dl>
          {#if state?.sync.error}<p class="inline-error">
              {coreErrorText(state.sync.error, language)}
            </p>{/if}
        {/if}
      </div>
    </div>
  </div>
{/if}

{#if showImport && state}
  <div class="modal-backdrop" role="presentation">
    <div
      class="import-dialog"
      use:modal={() => {
        showImport = false;
      }}
      role="dialog"
      aria-modal="true"
      aria-labelledby="import-heading"
      tabindex="-1"
    >
      <span class="import-icon"><Icon name="copy" size={24} /></span>
      <h2 id="import-heading">{t('importTitle')}</h2>
      <p>
        {t('importCount', { count: state.local_import_count })}
        <strong>{profileLabel(state.profile, t, language, preferences.timeZone)}</strong>.
      </p>
      <p class="help-text">
        {t('importHelp', { server: state.profile.server_url ?? '' })}
      </p>
      <div class="dialog-actions">
        <button
          class="secondary-button"
          disabled={busy}
          on:click={() => {
            showImport = false;
          }}>{t('importLater')}</button
        ><button class="primary-button" disabled={busy} on:click={() => run(() => importNotes())}
          >{t('importSubmit', { count: state.local_import_count })}</button
        >
      </div>
      {#if failure}<p class="inline-error">{errorText(failure, language)}</p>{/if}
    </div>
  </div>
{/if}

{#if markdownConfirmProfile}
  <div class="modal-backdrop" role="presentation">
    <div
      class="import-dialog"
      use:modal={() => {
        markdownConfirmProfile = null;
      }}
      role="dialog"
      aria-modal="true"
      aria-labelledby="markdown-heading"
      tabindex="-1"
    >
      <span class="import-icon"><Icon name="download" size={24} /></span>
      <h2 id="markdown-heading">{t('importMarkdown')}</h2>
      <p>
        <strong>{profileLabel(markdownConfirmProfile, t, language, preferences.timeZone)}</strong>
      </p>
      <p class="help-text">{t('importCloud')}</p>
      <div class="dialog-actions">
        <button
          class="secondary-button"
          disabled={busy}
          on:click={() => {
            markdownConfirmProfile = null;
          }}>{t('cancel')}</button
        >
        <button
          class="primary-button"
          disabled={busy}
          on:click={() => {
            const target = markdownConfirmProfile;
            markdownConfirmProfile = null;
            void run(async () => {
              if (target?.id !== state?.profile.id) throw new LocalizedError('profileChanged');
              await importMarkdown(true);
            });
          }}>{t('importMarkdown')}</button
        >
      </div>
    </div>
  </div>
{/if}

{#if purgeConfirmation}
  <div class="modal-backdrop" role="presentation">
    <div
      class="import-dialog"
      use:modal={() => {
        purgeConfirmation = null;
      }}
      role="dialog"
      aria-modal="true"
      aria-labelledby="purge-heading"
      tabindex="-1"
    >
      <h2 id="purge-heading">{t('purgeConfirm')}</h2>
      <p>{t('purgeHelp')}</p>
      <div class="dialog-actions">
        <button
          class="secondary-button"
          disabled={busy}
          on:click={() => {
            purgeConfirmation = null;
          }}>{t('cancel')}</button
        >
        <button class="danger" disabled={busy} on:click={() => run(confirmPurge)}
          >{t('purgeNote')}</button
        >
      </div>
      {#if failure}<p class="inline-error">{errorText(failure, language)}</p>{/if}
    </div>
  </div>
{/if}

{#if undoGroup}
  <div
    class="trash-snackbar"
    role="status"
    on:mouseenter={() => clearTimeout(undoTimer)}
    on:focusin={() => clearTimeout(undoTimer)}
    on:mouseleave={(event) => {
      if (!event.currentTarget.contains(document.activeElement)) restartUndoTimer();
    }}
    on:focusout={(event) => {
      if (!event.currentTarget.contains(event.relatedTarget as Node | null)) restartUndoTimer();
    }}
  >
    <span class="snackbar-mark" aria-hidden="true"><Icon name="check" size={18} /></span>
    <span class="snackbar-message">{t('trashNotice', { total: undoGroup.notes.length })}</span>
    <button
      class="snackbar-undo"
      disabled={busy}
      aria-label={t('undoTrash')}
      on:click={() => run(undoTrash)}><Icon name="rotate-ccw" size={15} />{t('undoShort')}</button
    >
    <button class="snackbar-dismiss" aria-label={t('dismiss')} on:click={clearUndo}
      ><Icon name="x" size={16} /></button
    >
  </div>
{/if}
