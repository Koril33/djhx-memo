<script lang="ts">
  import { authenticationAccepted } from './lib/account-state';
  import { brandNames } from './lib/brand';
  import { onMount, onDestroy, tick } from 'svelte';
  import { listen, type UnlistenFn } from '@tauri-apps/api/event';
  import Editor from './Editor.svelte';
  import type { EditorView } from '@codemirror/view';
  import { splitScroll } from './lib/split-scroll';
  import GlobalSearch from './GlobalSearch.svelte';
  import Help from './Help.svelte';
  import Icon from './Icon.svelte';
  import Updates from './Updates.svelte';
  import { call, native, errorText, coreErrorText } from './lib/bridge';
  import { EditorSession } from './lib/editor-session';
  import { renderMarkdown } from './lib/markdown';
  import type { CodeHighlights } from './lib/code-highlights';
  import { isLargeNote } from './lib/note-size';
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
  import type {
    AppState,
    Device,
    EditorFindApi,
    Note,
    Profile,
    RuntimeInfo,
    SearchHit,
  } from './lib/types';

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
    if (result.purge_state === 'purged') {
      enterWelcome();
      documentKey++;
    } else {
      enterNote();
    }
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
    document.title = brandNames[language === 'zh-CN' ? 'zh-CN' : 'en'];
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
        enterWelcome();
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
      enterNote();
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
  type EditorPage = 'welcome' | 'draft' | 'note';
  let editorPage: EditorPage = 'welcome';
  let transitioning = false;
  let booting = true;
  let busy = false;
  let lifecyclePending = false;
  let notice: Message | null = null;
  let failure: unknown = null;
  let settings = false;
  let settingsTab: 'account' | 'devices' | 'data' | 'appearance' | 'editor' | 'updates' =
    'appearance';
  let sourceView: EditorView | undefined;
  let openLint: (() => void) | undefined;
  let authMode: 'login' | 'register' = 'login';
  let authVisible = false;
  let reauthProfile: Profile | null = null;
  $: hasSavedAccounts = state?.profiles.some((profile) => profile.kind === 'account') ?? false;
  $: showAuthForm = authVisible || (!!state && !hasSavedAccounts && state.profile.kind === 'local');
  function openAuthForm(reauth = false): void {
    reauthProfile = reauth && state?.profile.kind === 'account' ? { ...state.profile } : null;
    authMode = 'login';
    serverUrl = reauthProfile?.server_url ?? addServerDraft;
    username = reauthProfile?.username ?? '';
    password = '';
    invite = '';
    authVisible = true;
  }
  function closeAuthForm(): void {
    authVisible = false;
    reauthProfile = null;
    password = '';
    invite = '';
  }

  let addServerDraft = DEFAULT_SERVER_URL;
  let serverUrl = addServerDraft;
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
  let editorFocused = false;
  let modalDepth = 0;
  let globalSearchOpen = false;
  let findApi: EditorFindApi | undefined;
  let pendingFindQuery: string | null = null;
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
  // Source mode hides the preview pane, so skip the expensive Markdown parse/HTML
  // generation until preview or split is actually shown. Preview/split recompute on
  // the next render because `mode` is a reactive dependency here.
  $: preview =
    mode === 'source'
      ? ''
      : renderMarkdown(
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
    // Optional code coloring is skipped for large notes (D1: 128 KiB UTF-8, matching
    // the core shared highlight cap) so we never ship a full body over the bridge for
    // decoration the core would refuse. The body, save cadence and find stay intact.
    if (isLargeNote(source)) return;
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
  let helpPointerOrigin = false;
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
    editorPage === 'welcome'
      ? 'welcomeStatus'
      : editor.error
        ? 'saveFailed'
        : editor.saving || editor.unsaved || editor.composing
          ? 'saving'
          : editor.note
            ? 'saved'
            : 'blankDraft',
  );
  $: saveShortLabel = t(
    editorPage === 'welcome'
      ? 'welcomeStatus'
      : editor.error
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
      enterWelcome();
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
          enterNote();
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
    enterNote();
    documentKey++;
    notice = null;
  }
  async function newNote(): Promise<void> {
    clearListActions();
    if (state?.profile.read_only) throw new LocalizedError('recoveryReadOnlyError');
    await editor.open(null);
    enterDraft();
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
    enterWelcome();
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
    enterWelcome();
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
    const submittedMode = authMode;
    if (reauthProfile && state?.profile.id !== reauthProfile.id)
      throw new LocalizedError('authSpaceChanged');
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
      const result = await call<{ import_count: number; profile: Profile }>({
        action: submittedMode,
        server_url: serverUrl,
        username,
        password,
        invite_code: invite,
        device_name: deviceName,
      });
      await editor.open(null);
      enterWelcome();
      documentKey++;
      clearListActions();
      clearUndo();
      pendingMarkdown = null;
      state = null;
      query = '';
      trash = false;
      await refreshState(true);
      if (!authenticationAccepted(result.profile?.id, state))
        throw new LocalizedError('authSpaceChanged');
      closeAuthForm();
      settings = result.import_count === 0;
      settingsTab = 'account';
      showImport = result.import_count > 0;
      notify(submittedMode === 'register' ? 'registered' : 'signedIn');
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
    enterWelcome();
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
    enterNote();
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
  async function exportCurrentNote(): Promise<void> {
    const content = editor.exportSnapshot();
    if (!content.trim()) return;
    const path = await native<string | null>('export_note', { content });
    if (path) notify('noteExported', { path });
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
      enterWelcome();
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
    closeMenus();
    settingsTab = tab;
    settings = true;
    const content = document.querySelector<HTMLElement>('.settings-dialog .dialog-content');
    if (content) content.scrollTop = 0;
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
    modalDepth++;
    const previous = document.activeElement as HTMLElement | null;
    const focusable = () =>
      [
        ...node.querySelectorAll<HTMLElement>(
          'button:not(:disabled), input:not(:disabled), select:not(:disabled), summary, [tabindex="0"]',
        ),
      ].filter(
        (element) =>
          element.getClientRects().length > 0 &&
          (element.tagName === 'SUMMARY' || !element.closest('details:not([open])')),
      );
    queueMicrotask(() => (focusable()[0] ?? node).focus());
    const keydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) {
        const openMenu = node.querySelector<HTMLDetailsElement>('details[data-menu][open]');
        if (openMenu) {
          event.preventDefault();
          openMenu.open = false;
          openMenu.querySelector('summary')?.focus();
          return;
        }
        if (
          document.activeElement instanceof HTMLElement &&
          document.activeElement.closest('[data-help].open')
        ) {
          event.preventDefault();
          window.dispatchEvent(new Event('djhx-esc-help'));
          return;
        }
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
        modalDepth--;
        node.removeEventListener('keydown', keydown);
        previous?.focus();
      },
    };
  }

  function receiveFindApi(api: EditorFindApi): void {
    findApi = api;
    if (pendingFindQuery !== null) {
      const term = pendingFindQuery;
      pendingFindQuery = null;
      api.run(term);
    }
  }
  function handleEditorFocus(): void {
    editorFocused = true;
  }
  function handleEditorBlur(): void {
    editorFocused = false;
    void editor.flush().catch((error) => {
      failure = error;
    });
  }
  function focusNoteList(): void {
    const target =
      document.querySelector<HTMLElement>('#note-list .note-card') ??
      document.querySelector<HTMLElement>('#note-list .search input');
    target?.focus();
  }
  function focusWelcome(): void {
    const target =
      document.querySelector<HTMLElement>('.welcome-action:not(:disabled)') ??
      document.querySelector<HTMLElement>('.welcome');
    target?.focus();
  }
  function enterWelcome(): void {
    editorPage = 'welcome';
    findApi = undefined;
    pendingFindQuery = null;
    clearListActions();
    void tick().then(focusWelcome);
  }
  function enterDraft(): void {
    editorPage = 'draft';
    pendingFindQuery = null;
  }
  function enterNote(): void {
    editorPage = 'note';
  }
  async function closeCurrentDocument(): Promise<void> {
    if (transitioning) return;
    transitioning = true;
    try {
      await editor.closeDocument();
      enterWelcome();
    } catch (error) {
      failure = error;
    } finally {
      transitioning = false;
    }
  }
  function closeWelcomeWindow(): void {
    if (
      editorPage !== 'welcome' ||
      modalDepth > 0 ||
      busy ||
      booting ||
      lifecyclePending ||
      transitioning
    )
      return;
    void native('request_hide').catch((error) => {
      failure = error;
    });
  }
  function openGlobalSearch(): void {
    globalSearchOpen = true;
  }
  async function openInNoteFind(): Promise<void> {
    if (!editor.note) return;
    if (mode !== 'source') {
      mode = 'source';
      await tick();
    }
    findApi?.open();
  }
  async function openGlobalSearchHit(hit: SearchHit, term: string): Promise<void> {
    await run(async () => {
      if (mode === 'preview') mode = 'source';
      if (state?.profile.id !== hit.profile_id) {
        await switchProfile(hit.profile_id);
      }
      await editor.flush();
      const note = await call<Note>({
        action: 'get',
        profile_id: hit.profile_id,
        id: hit.note_id,
      });
      await editor.open(note);
      enterNote();
      pendingFindQuery = term;
      documentKey++;
      trash = hit.deleted;
      clearListActions();
      await refreshNotes();
      notice = null;
    });
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
        if (editorPage === 'welcome') void tick().then(focusWelcome);
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
  on:pointerdown={(event) => {
    helpPointerOrigin = event.target instanceof Element && !!event.target.closest('[data-help]');
    closeMenus(event.target instanceof Node ? event.target : undefined);
  }}
  on:click={(event) => {
    // Help disclosures close only on the click phase and only when the pointer
    // both landed and went down outside every help. Closing on pointerdown would
    // shift the layout before a neighbouring disclosure's activation completed,
    // swallowing its click; helps never exclude each other. Tracking the
    // pointerdown origin keeps a drag-select that overshoots the description
    // from collapsing the text under the just-created selection.
    if (
      !helpPointerOrigin &&
      event.target instanceof Element &&
      !event.target.closest('[data-help]')
    ) {
      window.dispatchEvent(new Event('djhx-close-help'));
    }
  }}
  on:focusin={(event) => {
    // Clicking non-focusable prose may focus body. Do not collapse the text
    // beneath an active selection; the window click handler closes helps.
    if (
      event.target instanceof HTMLElement &&
      event.target !== document.body &&
      event.target !== document.documentElement
    )
      closeMenus(event.target);
  }}
  on:blur={blur}
  on:focus={foreground}
  on:online={() => {
    void call({ action: 'sync' }).catch(() => {});
  }}
  on:keydown={(event) => {
    // Guard 1: an inner handler (editor keymap, find panel, modal) already consumed the key.
    if (event.defaultPrevented) return;
    // Guard 2: never intercept during IME composition.
    if (event.isComposing || event.keyCode === 229) return;
    // Guard 3: modal context blocks navigation shortcuts, except saving.
    const modalOpen = modalDepth > 0;
    if (event.ctrlKey && !event.altKey) {
      const key = event.key.toLowerCase();
      if (key === 'n') {
        event.preventDefault();
        if (!modalOpen) void run(newNote);
        return;
      }
      if (key === 's') {
        event.preventDefault();
        void run(() => editor.flush());
        return;
      }
      if (key === 'w' && !event.shiftKey) {
        event.preventDefault();
        if (modalOpen || event.repeat || busy || lifecyclePending || transitioning) return;
        if (editorPage === 'welcome') closeWelcomeWindow();
        else void run(closeCurrentDocument);
        return;
      }
      if (key === 'f' && !event.shiftKey) {
        event.preventDefault();
        if (!modalOpen) void openInNoteFind();
        return;
      }
      if (key === 'f' && event.shiftKey) {
        event.preventDefault();
        if (!modalOpen) openGlobalSearch();
        return;
      }
    }
    if (event.key === 'Escape') {
      if (editorFocused) {
        event.preventDefault();
        focusNoteList();
      } else {
        closeMenus(undefined, true);
        if (selecting && !busy) clearListActions();
      }
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
        on:click={() => run(() => changeSection(true))}><Icon name="recycle" /></button
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
              class="icon-button list-select-button"
              title={t('selectNotes')}
              disabled={busy || !state || state.profile.read_only}
              aria-label={t('selectNotes')}
              on:click={() => {
                selecting = true;
                selection = new Set();
              }}
            >
              <Icon name="list-checks" size={18} />
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
                ><Icon name="file-input" />{t('importMarkdown')}</button
              >
              {#if pendingMarkdown}<button
                  on:click={() => {
                    pendingMarkdown = null;
                  }}><Icon name="x" />{t('cancel')} · {t('importMarkdown')}</button
                >{/if}
              <button disabled={!state || busy} on:click={(event) => menuAction(event, exportNotes)}
                ><Icon name="file-output" />{t(trash ? 'exportTrash' : 'exportNotes')}</button
              >
              <button on:click={(event) => menuAction(event, () => openSettings('account'))}
                ><Icon name="user" />{t('accountTab')}</button
              >
              <button on:click={(event) => menuAction(event, () => openSettings())}
                ><Icon name="settings" />{t('settings')}</button
              >
            </div>
          </details>
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
      {#if editorPage !== 'welcome'}<strong class="document-title"
          >{editor.note ? title : t('newDraft')}</strong
        >{/if}
      {#if editorPage !== 'welcome'}<div class="editor-actions">
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
          {#if mode === 'split'}<button
              class="icon-button"
              class:chosen={preferences.splitScrollSync}
              aria-pressed={preferences.splitScrollSync}
              aria-label={t('splitScrollSync')}
              title={t('splitScrollSync')}
              on:click={() => {
                preferences.splitScrollSync = !preferences.splitScrollSync;
                updatePreferences();
              }}><Icon name="arrow-down-up" size={18} /></button
            >{/if}
          {#if preferences.markdownLintEnabled}<button
              class="icon-button"
              aria-label={t('lintProblems')}
              title={t('lintProblems')}
              on:click={() => {
                mode = 'source';
                openLint?.();
              }}><Icon name="spell-check" size={18} /></button
            >{/if}
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
                  disabled={busy ||
                    state?.profile.read_only ||
                    editor.note.purge_state === 'pending'}
                  on:click={(event) => menuAction(event, () => setDeleted(false))}
                  ><Icon name="rotate-ccw" />{t('restoreNote')}</button
                >{/if}
              <button
                disabled={busy || !editor.content}
                on:click={(event) => menuAction(event, copyBuffer)}
                ><Icon name="copy" />{t('copyText')}</button
              >
              <button
                disabled={busy ||
                  !editor.content.trim() ||
                  ['pending', 'purged'].includes(editor.note?.purge_state ?? '')}
                on:click={(event) => menuAction(event, exportCurrentNote)}
                ><Icon name="file-output" />{t('exportCurrentNote')}</button
              >
              <hr />
              {#if editor.note?.deleted && editor.note.purge_state !== 'purged'}<button
                  class="danger"
                  disabled={busy ||
                    state?.profile.read_only ||
                    editor.note.purge_state === 'pending'}
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
            </div>
          </details>
        </div>{/if}
    </header>
    {#if runtime && !runtime.tray_available}<div
        class="info-strip compact-notice"
        title={t('trayUnavailable')}
      >
        <Icon name="info" size={16} /><span>{t('closeToQuitHelp')}</span>
      </div>{/if}
    {#if state && !state.credential_persistent && state.profile.kind === 'account' && state.sync.state !== 'auth_required'}<div
        class="info-strip warning-strip"
      >
        {t('keyringUnavailable')}
      </div>{/if}
    {#if state?.sync.state === 'auth_required'}<div class="info-strip warning-strip">
        {t('signInAgainHelp')}<button
          on:click={() =>
            run(async () => {
              openAuthForm(true);
              await openSettings('account');
            })}>{t('signInAgain')}</button
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
      </div>{:else if editorPage === 'welcome'}<div
        class="welcome"
        role="region"
        aria-label={t('noteEditor')}
      >
        <div class="welcome-panel">
          <div class="welcome-actions">
            <button
              class="welcome-action"
              disabled={busy ||
                booting ||
                !!state?.profile.read_only ||
                activeMismatch ||
                lifecyclePending}
              title={state?.profile.read_only
                ? t('recoveryReadOnlyError')
                : busy || lifecyclePending
                  ? t('processing')
                  : t('newNote')}
              on:click={() => run(newNote)}
            >
              <Icon name="plus" size={20} /><span class="welcome-action-label">{t('newNote')}</span
              ><kbd>Ctrl+N</kbd>
            </button>
            <button class="welcome-action" on:click={openGlobalSearch}>
              <Icon name="search" size={20} /><span class="welcome-action-label"
                >{t('searchNotes')}</span
              ><kbd>Ctrl+Shift+F</kbd>
            </button>
            <button
              class="welcome-action"
              disabled={busy || booting || lifecyclePending || transitioning}
              on:click={closeWelcomeWindow}
            >
              <Icon name="log-out" size={20} /><span class="welcome-action-label"
                >{t('closeWindow')}</span
              ><kbd>Ctrl+W</kbd>
            </button>
          </div>
        </div>
      </div>{:else}<div
        class="editor-body"
        class:split={mode === 'split'}
        use:splitScroll={{
          enabled: mode === 'split' && preferences.splitScrollSync,
          view: sourceView,
          version: previewSnapshot.version,
        }}
      >
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
              lintEnabled={preferences.markdownLintEnabled}
              onView={(view) => {
                sourceView = view;
              }}
              onLintApi={(open) => {
                openLint = open;
              }}
              readonly={readOnly}
              onEdit={(text) => editor.edit(text)}
              onComposition={(active) => editor.setComposing(active)}
              onFocus={handleEditorFocus}
              onBlur={handleEditorBlur}
              onFindApi={receiveFindApi}
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
            name={editorPage === 'welcome'
              ? 'file-text'
              : editor.error
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
          <div class="status-panel-heading">
            <h3>{t('statusDetails')}</h3>
            <button
              class="icon-button"
              aria-label={t('close')}
              title={t('close')}
              on:click={(event) => {
                const menu = event.currentTarget.closest('details');
                if (menu) {
                  menu.open = false;
                  menu.querySelector('summary')?.focus();
                }
              }}><Icon name="x" size={18} /></button
            >
          </div>
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
          <div class="status-panel-actions">
            <button
              class="secondary-button"
              disabled={busy}
              on:click={(event) => menuAction(event, () => openSettings('account'))}
            >
              <Icon name="settings" size={16} />{t('spaceSettings')}
            </button>
            {#if state?.profile.kind === 'account'}<button
                class="primary-button"
                disabled={busy}
                on:click={(event) => menuAction(event, synchronize)}
              >
                <Icon name="refresh-cw" size={16} />{t('syncNow')}
              </button>{/if}
          </div>
        </div>
      </details>
      <span class="character-count"
        >{t('characters', { count: [...editor.content].length.toLocaleString(language) })}</span
      >
    </footer>
  </section>
</main>

{#if globalSearchOpen}
  <div class="modal-backdrop" role="presentation">
    <div
      class="global-search-dialog"
      use:modal={() => {
        globalSearchOpen = false;
      }}
      role="dialog"
      aria-modal="true"
      aria-label={t('globalSearch')}
      tabindex="-1"
    >
      <GlobalSearch
        {language}
        timeZone={preferences.timeZone}
        profileId={state?.profile.id ?? 'local'}
        profiles={state?.profiles ?? []}
        onClose={() => {
          globalSearchOpen = false;
        }}
        onOpenNote={openGlobalSearchHit}
      />
    </div>
  </div>
{/if}

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
          class:chosen={settingsTab === 'editor'}
          on:click={() => run(() => openSettings('editor'))}>{t('editorSettings')}</button
        >
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
          <div class="account-entry-actions">
            {#if state?.profile.kind === 'account' && state.sync.state === 'auth_required'}
              <button class="primary-button" disabled={busy} on:click={() => openAuthForm(true)}
                >{t('signInAgain')}</button
              >
            {/if}
            {#if hasSavedAccounts}<button
                class="secondary-button"
                disabled={busy}
                on:click={() => openAuthForm()}
                ><Icon name="plus" size={16} />{t('addAccount')}</button
              >{/if}
          </div>
          {#if showAuthForm}<div class="auth-form-panel">
              <div class="auth-heading">
                <Help
                  id="account-help"
                  label={t('helpTitle')}
                  heading={reauthProfile
                    ? t('signInAgain')
                    : authMode === 'login'
                      ? t(hasSavedAccounts ? 'loginOther' : 'signInTitle')
                      : t('registerInvite')}>{t('accountHelp')}</Help
                >
                {#if !reauthProfile}<button
                    class="text-button"
                    disabled={busy}
                    on:click={() => {
                      authMode = authMode === 'login' ? 'register' : 'login';
                    }}
                  >
                    {authMode === 'login' ? t('registerLink') : t('loginLink')}
                  </button>{/if}
                {#if hasSavedAccounts}<button
                    class="icon-button"
                    disabled={busy}
                    aria-label={t('close')}
                    on:click={closeAuthForm}><Icon name="x" size={18} /></button
                  >{/if}
              </div>
              <form
                aria-describedby="account-help-text"
                novalidate
                on:submit|preventDefault={() => run(authenticate)}
              >
                <fieldset disabled={busy}>
                  <label class="field"
                    >{t('serverAddress')}<input
                      type="url"
                      required
                      bind:value={serverUrl}
                      on:input={(event) => {
                        if (!reauthProfile) addServerDraft = event.currentTarget.value;
                      }}
                      readonly={!!reauthProfile}
                      placeholder={DEFAULT_SERVER_URL}
                      autocomplete="url"
                    />{#if runtime?.debug_http}<small>{t('debugServerHelp')}</small>{/if}</label
                  >
                  <div class="field-row">
                    <label class="field"
                      >{t('username')}<input
                        required
                        bind:value={username}
                        readonly={!!reauthProfile}
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
                  <button class="primary-button auth-submit" disabled={busy}
                    >{busy
                      ? t('processing')
                      : authMode === 'login'
                        ? t('loginSubmit')
                        : t('registerSubmit')}</button
                  >
                </fieldset>
              </form>
            </div>{/if}
        {:else if settingsTab === 'devices'}
          <Help id="devices-help" heading={t('devicesTab')} label={t('helpTitle')}
            >{t('devicesHelp')}</Help
          >
          {#if state?.profile.kind !== 'account'}<div class="settings-empty">
              {t('devicesNeedAccount')}
            </div>{:else}<div class="device-list" aria-describedby="devices-help-text">
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
        {:else if settingsTab === 'editor'}
          <div class="field help-field">
            <Help id="lint-help" heading={t('markdownLint')} label={t('helpTitle')}
              >{t('lintHelp')}</Help
            >
            <input
              aria-labelledby="lint-help-label"
              aria-describedby="lint-help-text"
              type="checkbox"
              role="switch"
              bind:checked={preferences.markdownLintEnabled}
              on:change={updatePreferences}
            />
          </div>
          <label class="field"
            >{t('splitScrollSync')}<input
              type="checkbox"
              role="switch"
              bind:checked={preferences.splitScrollSync}
              on:change={updatePreferences}
            /></label
          >
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
          <Help id="export-help" heading={t('exportSpace')} label={t('helpTitle')}
            >{t('exportHelp')}</Help
          >
          <button
            aria-describedby="export-help-text"
            class="secondary-button"
            disabled={busy}
            on:click={() => run(exportNotes)}
            ><Icon name="file-output" size={18} />{t(
              trash ? 'exportDeleted' : 'exportActive',
            )}</button
          >
          <div class="settings-separator"></div>
          <Help id="copy-help" heading={t('copySpace')} label={t('helpTitle')}
            >{t('copySpaceHelp')}</Help
          >
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
              aria-describedby="copy-help-text"
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
      <span class="import-icon"><Icon name="file-input" size={24} /></span>
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
        <button class="danger destructive-solid" disabled={busy} on:click={() => run(confirmPurge)}
          ><Icon name="trash-2" size={18} />{t('purgeNote')}</button
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
