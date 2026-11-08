<script lang="ts">
  import { onMount, onDestroy } from 'svelte';
  import { listen, type UnlistenFn } from '@tauri-apps/api/event';
  import Editor from './Editor.svelte';
  import { call, native, errorText, coreErrorText } from './lib/bridge';
  import { EditorSession } from './lib/editor-session';
  import { renderMarkdown } from './lib/markdown';
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
  import type { AppState, Device, Note, RuntimeInfo } from './lib/types';

  let preferences: Preferences = (() => {
    try {
      return loadPreferences(localStorage);
    } catch {
      return { ...defaultPreferences };
    }
  })();
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
  let query = '';
  let trash = false;
  let mode: 'source' | 'preview' | 'split' = 'source';
  let booting = true;
  let busy = false;
  let lifecyclePending = false;
  let notice: Message | null = null;
  let failure: unknown = null;
  let settings = false;
  let settingsTab: 'account' | 'devices' | 'data' | 'appearance' = 'account';
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
  $: preview = renderMarkdown(previewSnapshot.source, !readOnly, previewSnapshot.version, language);
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
    busy ||
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
  function excerpt(note: Note, translate = t): string {
    return (
      note.content
        .split('\n')
        .filter((line) => line.trim())
        .slice(1)
        .join(' ')
        .slice(0, 90) || translate('excerptEmpty')
    );
  }
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
    if (editor.note) {
      const selected = notes.find((note) => note.id === editor.note?.id);
      if (selected) editor.acceptRemote(selected);
      else if (!editor.unsaved && !editor.saving) {
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
      trash = false;
      query = '';
    }
    const changed =
      force || !state || next.profile.id !== state.profile.id || next.event_seq !== state.event_seq;
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
    if (state?.profile.read_only) throw new LocalizedError('recoveryReadOnlyError');
    await editor.open(null);
    documentKey++;
    trash = false;
    query = '';
    await refreshNotes();
    notice = null;
  }
  async function changeSection(deleted: boolean): Promise<void> {
    await editor.flush();
    trash = deleted;
    query = '';
    await editor.open(null);
    documentKey++;
    await refreshNotes();
  }
  async function switchProfile(id: string): Promise<void> {
    if (state?.profile.id === id) return;
    await editor.flush();
    stateRequestCounter++;
    await call({ action: 'switch', profile_id: id });
    await editor.open(null);
    documentKey++;
    query = '';
    trash = false;
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
  async function openSettings(tab: typeof settingsTab = 'account'): Promise<void> {
    settingsTab = tab;
    settings = true;
    if (tab === 'devices' && state?.profile.kind === 'account')
      devices = await call<Device[]>({ action: 'devices' });
  }
  async function revoke(id: string): Promise<void> {
    await call({ action: 'revoke_device', id });
    devices = await call<Device[]>({ action: 'devices' });
    await refreshState(true);
    notify('revokedNotice');
  }
  function search(): void {
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
    clearInterval(poll);
    clearTimeout(searchTimer);
    unlisteners.forEach((stop) => stop());
    editor.dispose();
  });
</script>

<svelte:window
  on:blur={blur}
  on:focus={foreground}
  on:online={() => {
    void call({ action: 'sync' }).catch(() => {});
  }}
  on:keydown={(event) => {
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

<main class="app-shell" aria-busy={booting}>
  <aside class="sidebar">
    <div class="brand">
      <strong>DJHX Memo</strong>
    </div>
    <button
      class="new-button"
      aria-label={t('newNote')}
      disabled={busy || booting || !!state?.profile.read_only || activeMismatch}
      on:click={() => run(newNote)}><span>＋</span> {t('newNote')} <kbd>Ctrl N</kbd></button
    >
    <div class="nav-caption">{t('workspace')}</div>
    <button
      class:active={!trash}
      class="nav-item"
      aria-label={t('allNotes')}
      on:click={() => run(() => changeSection(false))}><span>▤</span> {t('allNotes')}</button
    >
    <button
      class:active={trash}
      class="nav-item"
      aria-label={t('trash')}
      on:click={() => run(() => changeSection(true))}><span>♲</span> {t('trash')}</button
    >
    <div class="sidebar-rule"></div>
    <button
      class="nav-item muted"
      aria-label={t(trash ? 'exportTrash' : 'exportNotes')}
      on:click={() => run(exportNotes)}
      disabled={!state || busy}><span>↓</span> {t(trash ? 'exportTrash' : 'exportNotes')}</button
    >
    <button
      class="nav-item muted"
      aria-label={t('settings')}
      on:click={() => run(() => openSettings())}><span>⚙</span> {t('settings')}</button
    >
    <div class="sidebar-bottom">
      <div class="space-label">
        <span class="space-dot"></span>{state?.profile.kind === 'local'
          ? t('localSpace')
          : state?.profile.kind === 'recovery'
            ? t('recoverySpace')
            : t('accountSpace')}
      </div>
      <strong class="profile-name"
        >{state
          ? profileLabel(state.profile, t, language, preferences.timeZone)
          : t('openingEllipsis')}</strong
      >
      <small
        >{state?.profile.kind === 'account' ? state.profile.server_url : t('localTagline')}</small
      >
      <button class="space-switch" on:click={() => run(() => openSettings())}
        >{t('switchSpace')} <span>↗</span></button
      >
    </div>
  </aside>

  <section class="note-column" aria-label={t('noteList')}>
    <header class="list-header">
      <div>
        <h1>{trash ? t('trash') : t('allNotes')}</h1>
        <span>{t(query ? 'matches' : 'records', { count: notes.length })}</span>
      </div>
      <button
        class="icon-button"
        aria-label={t('refreshList')}
        title={t('refresh')}
        disabled={busy}
        on:click={() => run(() => refreshState(true))}>↻</button
      >
    </header>
    <label class="search"
      ><span>⌕</span><input
        aria-label={t('searchCurrent')}
        bind:value={query}
        on:input={search}
        placeholder={t('searchPlaceholder')}
      /><kbd>{t('localBadge')}</kbd></label
    >
    <div class="list-context">
      {trash ? t('trashContext') : query ? t('searchContext') : t('recentNotes')}
    </div>
    <div class="notes-scroll">
      {#each notes as note (note.id)}
        <button
          class="note-card"
          class:selected={editor.note?.id === note.id}
          on:click={() => run(() => selectNote(note))}
          disabled={busy}
        >
          <div class="note-card-title">{note.content.trim() ? note.title : t('untitled')}</div>
          <p>{excerpt(note, t)}</p>
          <div class="note-meta">
            <time>{time(note.updated_at, language, preferences.timeZone)}</time
            >{#if note.conflict}<span class="conflict-chip">{t('conflictCopy')}</span
              >{:else if note.sync_state === 'pending'}<span
                class="pending-dot"
                title={t('pendingShort')}
              ></span>{/if}
          </div>
        </button>
      {:else}
        <div class="list-empty">
          <span>{query ? '⌕' : trash ? '♲' : '✎'}</span><strong
            >{query ? t('noSearchResults') : trash ? t('emptyTrash') : t('emptyNotes')}</strong
          >
          <p>
            {query ? t('searchHelp') : trash ? t('trashHelp') : t('emptyHelp')}
          </p>
        </div>
      {/each}
    </div>
    <footer class="list-footer">
      <span
        class:warning={state?.sync.state === 'error' || state?.sync.state === 'auth_required'}
        class="status-dot"
      ></span>{syncLabel}
    </footer>
  </section>

  <section class="workspace" aria-label={t('noteEditor')}>
    <header class="workspace-header">
      <div class="breadcrumb">
        {state
          ? profileLabel(state.profile, t, language, preferences.timeZone)
          : t('localNotes')}<span>/</span><strong>{editor.note ? title : t('newDraft')}</strong>
      </div>
      <button
        class="sync-button"
        disabled={busy || state?.profile.kind !== 'account'}
        on:click={() => run(synchronize)}
        ><span class:spinning={state?.sync.state === 'syncing'}>↻</span>
        {state?.profile.kind === 'account' ? t('sync') : t('localOnly')}</button
      >
    </header>
    {#if runtime && !runtime.tray_available}<div class="info-strip">
        {t('trayUnavailable')}
      </div>{/if}
    {#if state && !state.credential_persistent && state.profile.kind === 'account'}<div
        class="info-strip warning-strip"
      >
        {t('keyringUnavailable')}
      </div>{/if}
    {#if state?.sync.state === 'auth_required'}<div class="info-strip warning-strip">
        {t('signInAgainHelp')}<button on:click={() => run(() => openSettings())}
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
          on:click={() => {
            failure = null;
          }}>×</button
        >
      </div>{:else if notice}<div class="message success-message" role="status">
        <span>{notice ? t(notice.key, notice.args) : ''}</span><button
          aria-label={t('dismiss')}
          on:click={() => {
            notice = null;
          }}>×</button
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
    {#if editor.note?.deleted}<div class="info-strip warning-strip">
        {t('deletedHelp')}<button
          disabled={busy || state?.profile.read_only}
          on:click={() => run(() => setDeleted(false))}>{t('restoreNote')}</button
        >
      </div>{/if}
    <div class="editor-toolbar">
      <div class="view-switch" aria-label={t('editorMode')}>
        <button
          class:chosen={mode === 'source'}
          on:click={() => {
            mode = 'source';
          }}>{t('source')}</button
        ><button
          class:chosen={mode === 'preview'}
          on:click={() => {
            mode = 'preview';
          }}>{t('preview')}</button
        ><button
          class:chosen={mode === 'split'}
          on:click={() => {
            mode = 'split';
          }}>{t('split')}</button
        >
      </div>
      <span class="format-hint">Markdown</span><button
        class="text-button danger"
        disabled={busy || !editor.note || !!editor.note.deleted || state?.profile.read_only}
        on:click={() => run(() => setDeleted(true))}>{t('moveToTrash')}</button
      >
    </div>
    {#if booting}<div class="boot-state">{t('openingLocal')}</div>{:else if !state}<div
        class="boot-state"
      >
        {t('openFailed')}<button on:click={() => run(() => refreshState(true))}>{t('retry')}</button
        >
      </div>{:else}<div class="editor-body" class:split={mode === 'split'}>
        {#if mode !== 'preview'}<div class="source-pane">
            {#key documentKey}<Editor
                value={editor.content}
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
          </div>{/if}
        {#if mode !== 'source'}<div class="preview-pane">
            <div class="preview-label">{t('readingPreview')}</div>
            <article
              class="markdown"
              use:previewEvents
              role="document"
              aria-label={t('safePreview')}
            >
              {#if editor.content}{@html preview}{:else}<div class="preview-empty">
                  <span>✎</span>
                  <h2>{t('previewEmptyTitle')}</h2>
                  <p>{t('previewEmptyHelp')}</p>
                </div>{/if}
            </article>
          </div>{/if}
      </div>{/if}
    <footer class="editor-footer">
      <span class:error={!!editor.error} class:saving={editor.saving || editor.unsaved}
        ><span class="small-dot"></span>{t(
          editor.error
            ? 'saveFailed'
            : editor.saving || editor.unsaved || editor.composing
              ? 'saving'
              : editor.note
                ? 'saved'
                : 'blankDraft',
        )}</span
      ><span
        >{t('characters', { count: [...editor.content].length.toLocaleString(language) })} <i>·</i>
        UTF-8 <i>·</i> LF</span
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
          <small>{t('settingsTagline')}</small>
          <h2 id="settings-heading">{t('settings')}</h2>
        </div>
        <button
          class="icon-button settings-close"
          aria-label={t('closeSettings')}
          disabled={busy}
          on:click={() => {
            settings = false;
          }}
          ><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg
          ></button
        >
      </header>
      <nav class="settings-tabs">
        <button
          class:chosen={settingsTab === 'account'}
          on:click={() => run(() => openSettings('account'))}>{t('accountTab')}</button
        ><button
          class:chosen={settingsTab === 'devices'}
          on:click={() => run(() => openSettings('devices'))}>{t('devicesTab')}</button
        ><button
          class:chosen={settingsTab === 'data'}
          on:click={() => run(() => openSettings('data'))}>{t('dataTab')}</button
        ><button
          class:chosen={settingsTab === 'appearance'}
          on:click={() => run(() => openSettings('appearance'))}>{t('appearanceTab')}</button
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
              >{state?.profile.kind === 'local' ? t('localAvatar') : t('cloudAvatar')}</span
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
                ><span
                  >{profile.kind === 'local' ? '▤' : profile.kind === 'recovery' ? '◇' : '☁'}</span
                >
                <div>
                  <strong>{profileLabel(profile, t, language, preferences.timeZone)}</strong><small
                    >{profile.kind === 'local'
                      ? t('localOnly')
                      : profile.kind === 'recovery'
                        ? t('readOnly')
                        : profile.server_url}</small
                  >
                </div>
                <span>{state?.profile.id === profile.id ? '✓' : '→'}</span></button
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
              /><small>{runtime?.debug_http ? t('debugServerHelp') : t('serverHelp')}</small></label
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
                  <span class="device-symbol">▣</span>
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
        {:else if settingsTab === 'appearance'}
          <h3>{t('appearanceTab')}</h3>
          <p class="help-text">{t('preferencesHelp')}</p>
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
            >↓ {t(trash ? 'exportDeleted' : 'exportActive')}</button
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
      <span class="import-icon">▤ → ☁</span>
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
