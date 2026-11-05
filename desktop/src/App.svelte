<script lang="ts">
  import { onMount, onDestroy } from 'svelte';
  import { listen, type UnlistenFn } from '@tauri-apps/api/event';
  import Editor from './Editor.svelte';
  import { call, native, errorText } from './lib/bridge';
  import { EditorSession } from './lib/editor-session';
  import { renderMarkdown } from './lib/markdown';
  import { PreviewState, taskSnapshotMatches } from './lib/preview-state';
  import type { AppState, Device, Note, RuntimeInfo } from './lib/types';

  let state: AppState | null = null;
  let runtime: RuntimeInfo | null = null;
  let notes: Note[] = [];
  let query = '';
  let trash = false;
  let mode: 'source' | 'preview' | 'split' = 'source';
  let booting = true;
  let busy = false;
  let lifecyclePending = false;
  let notice = '';
  let failure = '';
  let settings = false;
  let settingsTab: 'account' | 'devices' | 'data' = 'account';
  let authMode: 'login' | 'register' = 'login';
  let serverUrl = '';
  let username = '';
  let password = '';
  let invite = '';
  let deviceName = /Windows/i.test(navigator.userAgent) ? 'Windows 桌面' : 'Linux 桌面';
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
  $: preview = renderMarkdown(previewSnapshot.source, !readOnly, previewSnapshot.version);
  $: syncLabel = state
    ? {
        local: '仅本机',
        pending: `待同步 · ${state.sync.pending}`,
        syncing: '正在同步',
        synced: '已同步',
        offline: '离线 · 本地可用',
        auth_required: '需重新登录',
        recovery_required: '需恢复处理',
        error: '同步失败',
      }[state.sync.state]
    : '正在打开';
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
      ?.replace(/^\s*#{1,6}\s+/, '') ?? '无标题'),
  ]
    .slice(0, 80)
    .join('');

  function time(value: string | null): string {
    if (!value) return '尚无记录';
    const date = new Date(value);
    return Number.isNaN(date.valueOf())
      ? value
      : date.toLocaleString('zh-CN', {
          month: 'short',
          day: 'numeric',
          hour: '2-digit',
          minute: '2-digit',
        });
  }
  function excerpt(note: Note): string {
    return (
      note.content
        .split('\n')
        .filter((line) => line.trim())
        .slice(1)
        .join(' ')
        .slice(0, 90) || '静静记录，随时回来。'
    );
  }
  function notify(text: string): void {
    notice = text;
    failure = '';
  }

  async function run(work: () => Promise<void>): Promise<void> {
    if (busy || lifecyclePending) return;
    busy = true;
    failure = '';
    try {
      await work();
    } catch (error) {
      failure = errorText(error);
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
        failure = '当前核心空间已变化。此处输入仍保留，请先导出草稿，再进入当前空间。';
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
          notify('当前修改已保留为冲突副本，已为你打开。');
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
    notice = '';
  }
  async function newNote(): Promise<void> {
    if (state?.profile.read_only)
      throw new Error('恢复空间只读，请在账号设置中复制需要保留的笔记。');
    await editor.open(null);
    documentKey++;
    trash = false;
    query = '';
    await refreshNotes();
    notice = '';
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
    notify('已切换空间；各空间的数据相互独立。');
  }
  async function authenticate(): Promise<void> {
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
      notify(authMode === 'register' ? '注册成功，已进入账号空间。' : '登录成功，已进入账号空间。');
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
    notify(`已复制 ${result.imported} 条笔记；来源空间保留原文。`);
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
    notify(
      result.remote_revoked
        ? '已注销，账号本地内容仍保留。'
        : '已在本机注销。远程吊销尚未完成，请从另一台已登录设备吊销本设备。',
    );
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
    notify(deleted ? '已移到回收站，正文将保留。' : '已恢复笔记。');
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
        notify('预览内容已更新，请再次点击当前任务。');
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
    if (path) notify(`已导出 ZIP：${path}`);
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
    notify(`当前文字已保全为 Markdown：${path}`);
  }
  async function copyBuffer(): Promise<void> {
    await navigator.clipboard.writeText(editor.content);
    notify('已复制当前编辑文字。');
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
    notify('已吊销设备。设备本地内容保留，下一次联网时停止同步。');
  }
  function search(): void {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      void refreshNotes().catch((error) => {
        failure = errorText(error);
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
      failure = errorText(error);
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
              failure = errorText(error);
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
              failure = errorText(error);
            })
            .finally(() => {
              polling = false;
            });
        }, 1000);
      } catch (error) {
        failure = errorText(error);
      } finally {
        booting = false;
      }
    })();
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
      <span class="brand-icon">简</span>
      <div><strong>简记</strong><small>djhx memo</small></div>
    </div>
    <button
      class="new-button"
      disabled={busy || booting || !!state?.profile.read_only || activeMismatch}
      on:click={() => run(newNote)}><span>＋</span> 新建笔记 <kbd>Ctrl N</kbd></button
    >
    <div class="nav-caption">工作空间</div>
    <button class:active={!trash} class="nav-item" on:click={() => run(() => changeSection(false))}
      ><span>▤</span> 全部笔记</button
    >
    <button class:active={trash} class="nav-item" on:click={() => run(() => changeSection(true))}
      ><span>♲</span> 回收站</button
    >
    <div class="sidebar-rule"></div>
    <button class="nav-item muted" on:click={() => run(exportNotes)} disabled={!state || busy}
      ><span>↓</span> 导出 {trash ? '回收站' : '笔记'}</button
    >
    <button class="nav-item muted" on:click={() => run(() => openSettings())}
      ><span>⚙</span> 账号与设置</button
    >
    <div class="sidebar-bottom">
      <div class="space-label">
        <span class="space-dot"></span>{state?.profile.kind === 'local'
          ? '本机空间'
          : state?.profile.kind === 'recovery'
            ? '恢复保全空间'
            : '账号空间'}
      </div>
      <strong class="profile-name">{state?.profile.label || '正在打开…'}</strong>
      <small
        >{state?.profile.kind === 'account' ? state.profile.server_url : '文字先留在你身边'}</small
      >
      <button class="space-switch" on:click={() => run(() => openSettings())}
        >切换空间 <span>↗</span></button
      >
    </div>
  </aside>

  <section class="note-column" aria-label="笔记列表">
    <header class="list-header">
      <div>
        <h1>{trash ? '回收站' : '全部笔记'}</h1>
        <span>{notes.length} 条{query ? '匹配' : '记录'}</span>
      </div>
      <button
        class="icon-button"
        aria-label="刷新列表"
        title="刷新"
        disabled={busy}
        on:click={() => run(() => refreshState(true))}>↻</button
      >
    </header>
    <label class="search"
      ><span>⌕</span><input
        aria-label="搜索当前空间笔记"
        bind:value={query}
        on:input={search}
        placeholder="搜索文字…"
      /><kbd>本地</kbd></label
    >
    <div class="list-context">
      {trash ? '内容一直保留，随时可以恢复' : query ? '字面匹配 · 支持中文短词' : '最近记录'}
    </div>
    <div class="notes-scroll">
      {#each notes as note (note.id)}
        <button
          class="note-card"
          class:selected={editor.note?.id === note.id}
          on:click={() => run(() => selectNote(note))}
          disabled={busy}
        >
          <div class="note-card-title">{note.title || '无标题'}</div>
          <p>{excerpt(note)}</p>
          <div class="note-meta">
            <time>{time(note.updated_at)}</time>{#if note.conflict}<span class="conflict-chip"
                >冲突副本</span
              >{:else if note.sync_state === 'pending'}<span class="pending-dot" title="待同步"
              ></span>{/if}
          </div>
        </button>
      {:else}
        <div class="list-empty">
          <span>{query ? '⌕' : trash ? '♲' : '✎'}</span><strong
            >{query ? '没有找到笔记' : trash ? '回收站是空的' : '这里，等待第一个念头'}</strong
          >
          <p>
            {query
              ? '试试更短的文字，或清空搜索。'
              : trash
                ? '删除的笔记会留在这里。'
                : '点击新建，或者直接在右侧开始写。'}
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

  <section class="workspace" aria-label="笔记编辑器">
    <header class="workspace-header">
      <div class="breadcrumb">
        {state?.profile.kind === 'local' ? '本机笔记' : state?.profile.label}<span>/</span><strong
          >{editor.note ? title : '新草稿'}</strong
        >
      </div>
      <button
        class="sync-button"
        disabled={busy || state?.profile.kind !== 'account'}
        on:click={() => run(synchronize)}
        ><span class:spinning={state?.sync.state === 'syncing'}>↻</span>
        {state?.profile.kind === 'account' ? '同步' : '仅本机'}</button
      >
    </header>
    {#if runtime && !runtime.tray_available}<div class="info-strip">
        系统托盘不可用，关闭窗口会先保存并退出；你可以从应用菜单重新打开。
      </div>{/if}
    {#if state && !state.credential_persistent && state.profile.kind === 'account'}<div
        class="info-strip warning-strip"
      >
        系统密钥环不可用。凭据仅保留在本次进程，重启后需重新登录；笔记保存在本机。
      </div>{/if}
    {#if state?.sync.state === 'auth_required'}<div class="info-strip warning-strip">
        此设备需重新登录，本地编辑和导出继续可用。<button on:click={() => run(() => openSettings())}
          >重新登录</button
        >
      </div>{/if}
    {#if state?.sync.state === 'recovery_required'}<div class="info-strip warning-strip">
        服务器恢复代次变化，旧数据已保全。请在设置中查看恢复空间并选择复制。<button
          on:click={() => run(() => openSettings('data'))}>查看</button
        >
      </div>{/if}
    {#if state?.profile.read_only}<div class="info-strip">
        恢复空间只读。先导出保全，或切回账号空间后明确选择复制。
      </div>{/if}
    {#if failure}<div class="message error-message" role="alert">
        <span>{failure}</span><button
          aria-label="收起消息"
          on:click={() => {
            failure = '';
          }}>×</button
        >
      </div>{:else if notice}<div class="message success-message" role="status">
        <span>{notice}</span><button
          aria-label="收起消息"
          on:click={() => {
            notice = '';
          }}>×</button
        >
      </div>{/if}
    {#if editor.error || activeMismatch}<div class="save-rescue">
        <strong>当前输入仍保留在内存中</strong><span
          >{editor.generationConflict
            ? '本机版本已变化，另存副本可保留双方内容。'
            : '请重试保存，或先复制、导出此处文字。'}</span
        >
        <div>
          {#if !activeMismatch}<button
              on:click={() =>
                run(() => (editor.generationConflict ? editor.saveAsCopy() : editor.flush()))}
              >{editor.generationConflict ? '另存为新笔记' : '重试保存'}</button
            >{/if}<button on:click={() => run(copyBuffer)}>复制文字</button><button
            on:click={() => run(() => rescue(activeMismatch))}
            >{activeMismatch ? '导出草稿并进入当前空间' : '导出当前文字'}</button
          >
        </div>
      </div>{/if}
    {#if editor.note?.conflict}<div class="conflict-banner">
        <strong>冲突副本</strong><span
          >{editor.note.conflict.reason === 'stale_delete'
            ? '删除未执行，内容已保留为冲突副本。'
            : '修改与另一版本相遇，两份内容都已保留。'} 来源：{editor.note.conflict
            .source_device_name} · {time(editor.note.conflict.detected_at)}</span
        ><small>原笔记：{editor.note.conflict.source_note_id}</small>
      </div>{/if}
    {#if editor.note?.deleted}<div class="info-strip warning-strip">
        此笔记在回收站中。恢复后可以继续编辑。<button
          disabled={busy || state?.profile.read_only}
          on:click={() => run(() => setDeleted(false))}>恢复笔记</button
        >
      </div>{/if}
    <div class="editor-toolbar">
      <div class="view-switch" aria-label="编辑模式">
        <button
          class:chosen={mode === 'source'}
          on:click={() => {
            mode = 'source';
          }}>源码</button
        ><button
          class:chosen={mode === 'preview'}
          on:click={() => {
            mode = 'preview';
          }}>预览</button
        ><button
          class:chosen={mode === 'split'}
          on:click={() => {
            mode = 'split';
          }}>对照</button
        >
      </div>
      <span class="format-hint">Markdown</span><button
        class="text-button danger"
        disabled={busy || !editor.note || !!editor.note.deleted || state?.profile.read_only}
        on:click={() => run(() => setDeleted(true))}>移到回收站</button
      >
    </div>
    {#if booting}<div class="boot-state">正在打开本机空间…</div>{:else if !state}<div
        class="boot-state"
      >
        无法打开本机空间。<button on:click={() => run(() => refreshState(true))}>重试</button>
      </div>{:else}<div class="editor-body" class:split={mode === 'split'}>
        {#if mode !== 'preview'}<div class="source-pane">
            {#key documentKey}<Editor
                value={editor.content}
                readonly={readOnly}
                onEdit={(text) => editor.edit(text)}
                onComposition={(active) => editor.setComposing(active)}
                onBlur={() => {
                  void editor.flush().catch((error) => {
                    failure = errorText(error);
                  });
                }}
              />{/key}
          </div>{/if}
        {#if mode !== 'source'}<div class="preview-pane">
            <div class="preview-label">阅读预览</div>
            <article
              class="markdown"
              use:previewEvents
              role="document"
              aria-label="安全 Markdown 预览"
            >
              {#if editor.content}{@html preview}{:else}<div class="preview-empty">
                  <span>✎</span>
                  <h2>为想法留一处安静的地方</h2>
                  <p>切到源码，写下第一行。<br />它会自动保存到这台设备。</p>
                </div>{/if}
            </article>
          </div>{/if}
      </div>{/if}
    <footer class="editor-footer">
      <span class:error={!!editor.error} class:saving={editor.saving || editor.unsaved}
        ><span class="small-dot"></span>{editor.status}</span
      ><span
        >{[...editor.content].length.toLocaleString('zh-CN')} 字符 <i>·</i> UTF-8 <i>·</i> LF</span
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
          <small>你的文字，你的空间</small>
          <h2 id="settings-heading">账号与设置</h2>
        </div>
        <button
          class="icon-button"
          aria-label="关闭设置"
          disabled={busy}
          on:click={() => {
            settings = false;
          }}>×</button
        >
      </header>
      <nav class="settings-tabs">
        <button
          class:chosen={settingsTab === 'account'}
          on:click={() => run(() => openSettings('account'))}>空间与账号</button
        ><button
          class:chosen={settingsTab === 'devices'}
          on:click={() => run(() => openSettings('devices'))}>登录设备</button
        ><button
          class:chosen={settingsTab === 'data'}
          on:click={() => run(() => openSettings('data'))}>数据与导出</button
        >
      </nav>
      <div class="dialog-content">
        {#if failure}<div class="inline-error" role="alert">{failure}</div>{/if}
        {#if notice}<div class="inline-notice" role="status">{notice}</div>{/if}
        {#if settingsTab === 'account'}
          <h3>当前空间</h3>
          <div class="current-profile">
            <span class="avatar">{state?.profile.kind === 'local' ? '本' : '云'}</span>
            <div>
              <strong>{state?.profile.label}</strong><small
                >{state?.profile.kind === 'local'
                  ? '独立本机空间 · 不自动上传'
                  : state?.profile.server_url}</small
              >
            </div>
            {#if state?.profile.kind === 'account'}<button
                class="text-button"
                disabled={busy}
                on:click={() => run(logout)}>注销</button
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
                  <strong>{profile.label}</strong><small
                    >{profile.kind === 'local'
                      ? '仅本机'
                      : profile.kind === 'recovery'
                        ? '只读保全'
                        : profile.server_url}</small
                  >
                </div>
                <span>{state?.profile.id === profile.id ? '✓' : '→'}</span></button
              >{/each}
          </div>
          <div class="auth-heading">
            <h3>{authMode === 'login' ? '登录其他账号' : '使用邀请码注册'}</h3>
            <button
              class="text-button"
              on:click={() => {
                authMode = authMode === 'login' ? 'register' : 'login';
              }}>{authMode === 'login' ? '注册账号 →' : '已有账号，登录 →'}</button
            >
          </div>
          <form on:submit|preventDefault={() => run(authenticate)}>
            <label class="field"
              >服务器地址<input
                type="url"
                required
                bind:value={serverUrl}
                placeholder="https://memo.example.com"
                autocomplete="url"
              /><small
                >{runtime?.debug_http
                  ? '调试构建允许显式 HTTP；生产请使用 HTTPS。'
                  : '使用你自己的 HTTPS 服务端地址。'}</small
              ></label
            >
            <div class="field-row">
              <label class="field"
                >用户名<input
                  required
                  bind:value={username}
                  minlength="3"
                  maxlength="32"
                  pattern={'[a-z0-9][a-z0-9_-]{2,31}'}
                  autocomplete="username"
                  placeholder="your-name"
                /></label
              ><label class="field"
                >设备名称<input
                  required
                  bind:value={deviceName}
                  maxlength="128"
                  placeholder="例如：家里的电脑"
                /></label
              >
            </div>
            <label class="field"
              >密码<input
                type="password"
                required
                bind:value={password}
                minlength="12"
                maxlength="256"
                autocomplete={authMode === 'register' ? 'new-password' : 'current-password'}
                placeholder="12～128 个字符"
              /></label
            >
            {#if authMode === 'register'}<label class="field"
                >邀请码<input
                  required
                  bind:value={invite}
                  autocomplete="off"
                  placeholder="由服务器管理员提供"
                /></label
              >{/if}
            <p class="help-text">
              登录后进入独立账号空间。本机笔记保留，只有你明确选择导入时才复制到账号。
            </p>
            <button class="primary-button auth-submit" disabled={busy}
              >{busy
                ? '正在处理…'
                : authMode === 'login'
                  ? '登录并进入账号空间'
                  : '注册并进入账号空间'}</button
            >
          </form>
        {:else if settingsTab === 'devices'}
          <h3>登录设备</h3>
          <p class="help-text">吊销会停止设备后续联网同步，设备上的本地内容仍保留。</p>
          {#if state?.profile.kind !== 'account'}<div class="settings-empty">
              进入账号空间后，可以查看和管理设备。
            </div>{:else}<div class="device-list">
              {#each devices as device (device.id)}<div class="device-row">
                  <span class="device-symbol">▣</span>
                  <div>
                    <strong
                      >{device.name}
                      {#if device.current}<em>当前设备</em>{/if}</strong
                    ><small
                      >{device.revoked_at
                        ? '已吊销 · ' + time(device.revoked_at)
                        : '最近活动 · ' + time(device.last_seen_at)}</small
                    ><small>有效期至 {time(device.expires_at)}</small>
                  </div>
                  <button
                    class="text-button danger"
                    disabled={busy || !!device.revoked_at}
                    on:click={() => run(() => revoke(device.id))}
                    >{device.revoked_at ? '已吊销' : '吊销'}</button
                  >
                </div>{:else}<div class="settings-empty">
                  暂无设备记录。<button on:click={() => run(() => openSettings('devices'))}
                    >重新读取</button
                  >
                </div>{/each}
            </div>{/if}
        {:else}
          <h3>导出当前空间</h3>
          <p class="help-text">
            先保存当前输入，再将 Markdown 与清单导出为 ZIP。已有文件不会被覆盖。
          </p>
          <button class="secondary-button" disabled={busy} on:click={() => run(exportNotes)}
            >↓ 导出{trash ? '含回收站的内容' : '全部未删除笔记'}</button
          >
          <div class="settings-separator"></div>
          <h3>复制本机或恢复空间</h3>
          <p class="help-text">
            复制到当前账号时生成新笔记
            ID，来源始终保留。相同保存代次重复操作不会产生重复副本；后续编辑的来源不会自动联动。
          </p>
          {#if state?.profile.kind === 'account'}<label class="field"
              >来源空间<select bind:value={importSource}
                >{#each state.profiles.filter((p) => p.kind === 'local' || p.kind === 'recovery') as profile (profile.id)}<option
                    value={profile.id}>{profile.label}</option
                  >{/each}</select
              ></label
            >
            <p class="import-target">目标：{state.profile.label} · {state.profile.server_url}</p>
            <button
              class="secondary-button"
              disabled={busy}
              on:click={() => run(() => importNotes(importSource))}
              >明确复制到此账号{importSource === 'local'
                ? `（${state.local_import_count} 条）`
                : ''}</button
            >{:else}<div class="settings-empty">先进入目标账号空间，再选择复制来源。</div>{/if}
          <div class="settings-separator"></div>
          <h3>本地与云端状态</h3>
          <dl class="status-details">
            <dt>当前状态</dt>
            <dd>{syncLabel}</dd>
            <dt>最近成功同步</dt>
            <dd>{time(state?.sync.last_success ?? null)}</dd>
            <dt>窗口关闭行为</dt>
            <dd>{runtime?.tray_available ? '保存成功后进入托盘' : '保存成功后退出'}</dd>
            <dt>客户端版本</dt>
            <dd>{runtime?.version}</dd>
          </dl>
          {#if state?.sync.error}<p class="inline-error">
              {state.sync.error.message}（{state.sync.error.code}）
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
      <h2 id="import-heading">带上本机的记录？</h2>
      <p>
        有 {state.local_import_count} 条本机笔记可以复制到 <strong>{state.profile.label}</strong>。
      </p>
      <p class="help-text">
        原笔记留在本机。复制后的内容会同步到 {state.profile.server_url}，之后两份内容各自独立。
      </p>
      <div class="dialog-actions">
        <button
          class="secondary-button"
          disabled={busy}
          on:click={() => {
            showImport = false;
          }}>暂不导入</button
        ><button class="primary-button" disabled={busy} on:click={() => run(() => importNotes())}
          >导入 {state.local_import_count} 条本机笔记</button
        >
      </div>
      {#if failure}<p class="inline-error">{failure}</p>{/if}
    </div>
  </div>
{/if}
