<script lang="ts">
  import { onMount } from 'svelte';
  import Icon from './Icon.svelte';
  import { native } from './lib/bridge';
  import { runUpdateCommand, updateCommandPending } from './lib/update-commands';
  import { formatTime, type Preferences } from './lib/preferences';
  export let language: 'zh-CN' | 'en';
  export let timeZone: Preferences['timeZone'];
  export let install: () => Promise<void>;
  type Status = {
    phase: string;
    current_version: string;
    target: string;
    candidate?: { version: string; size: number; notes: string };
    error?: string;
    downloaded: number;
    last_checked: number;
    auto_check: boolean;
    download_policy: string;
    operation_pending?: boolean;
  };
  let status: Status | null = null;
  let installing = false;
  let preferencesPending = false;
  let installError = '';
  let polling = false;
  $: commandPending = $updateCommandPending;
  $: managed = status?.target === 'system-managed';
  $: errorCode = installError || (status?.error !== 'UPDATE_CANCELED' ? status?.error : '');
  $: phase = status?.phase ?? 'idle';
  const names: Record<string, [string, string]> = {
    idle: ['尚未检查', 'Not checked'],
    checking: ['正在检查', 'Checking'],
    available: ['发现新版本', 'Update available'],
    downloading: ['正在下载', 'Downloading'],
    ready: ['已校验，等待安装', 'Verified and ready'],
    up_to_date: ['已是最新可用版本', 'Up to date'],
    failed: ['更新未完成', 'Update failed'],
    canceled: ['下载已取消', 'Download canceled'],
    preparing: ['正在准备安装', 'Preparing installation'],
    completed: ['更新已完成', 'Update completed'],
  };
  const errors: Record<string, [string, string]> = {
    UPDATE_NETWORK: [
      '更新服务暂时不可达，可稍后重试。',
      'Update service unavailable. Try again later.',
    ],
    UPDATE_SIGNATURE: [
      '更新签名验证失败，已阻止安装。',
      'Invalid signature. Installation blocked.',
    ],
    UPDATE_PACKAGE: ['安装包校验失败，请重新下载。', 'Invalid package. Download it again.'],
    UPDATE_EXPIRED: [
      '发行目录已过期或系统时间异常。',
      'Release catalog expired or system clock is incorrect.',
    ],
    UPDATE_REPLAY: [
      '发行目录已过期或被替换，已阻止安装。',
      'Older or conflicting catalog blocked.',
    ],
    UPDATE_STORAGE: [
      '更新文件无法读写，请检查权限和磁盘空间。',
      'Check update storage permissions and free space.',
    ],
    UPDATE_INCOMPATIBLE: [
      '此版本涉及不兼容变更，请查看发布说明。',
      'This update requires a compatibility change. Review release notes.',
    ],
    UPDATE_UNSUPPORTED: ['此安装渠道由系统管理。', 'This installation is managed externally.'],
    UPDATE_CHANGED: [
      '更新已变化或撤回，请重新检查。',
      'Update changed or was withdrawn. Check again.',
    ],
    UPDATE_SAVE: [
      '未能安全保存全部空间，已暂停安装。',
      'Could not safely save all spaces. Installation paused.',
    ],
    UPDATE_INSTALL: [
      '安装未完成，可以重试或手动安装。',
      'Installation did not complete. Retry or install manually.',
    ],
    UPDATE_CATALOG: [
      '发行目录格式不受支持，已阻止安装。',
      'Unsupported release catalog. Installation blocked.',
    ],
    UPDATE_NOT_CONFIGURED: ['尚未配置正式更新签名。', 'Update signing is not configured.'],
    UPDATE_BUSY: ['已有更新任务正在进行。', 'An update is already running.'],
  };
  $: zh = language === 'zh-CN';
  $: busy =
    installing ||
    !!status?.operation_pending ||
    commandPending ||
    ['checking', 'downloading', 'preparing'].includes(status?.phase ?? '');
  function label(pair: [string, string] | undefined) {
    return pair?.[zh ? 0 : 1] ?? (zh ? '更新未完成，请重试。' : 'Update failed. Try again.');
  }
  async function action(name: string, fields: Record<string, unknown> = {}) {
    const ownsPending = name !== 'snapshot' && name !== 'cancel';
    if (ownsPending && commandPending) return;
    if (ownsPending) {
      installError = '';
    }
    try {
      const invoke = () => native<string>('update_invoke', { action: name, ...fields });
      const result = ownsPending ? await runUpdateCommand(invoke) : await invoke();
      if (result !== undefined) status = JSON.parse(result);
    } catch {
      if (name !== 'snapshot' || !status)
        installError = name === 'snapshot' ? 'UPDATE_STORAGE' : 'UPDATE_INSTALL';
    }
  }
  function primaryAction() {
    if (phase === 'ready') return apply();
    return action(phase === 'available' ? 'download' : 'check');
  }
  async function apply() {
    await runUpdateCommand(async () => {
      installing = true;
      installError = '';
      try {
        await install();
      } catch (e) {
        installError = e instanceof Error ? e.message : String(e);
      } finally {
        installing = false;
        await action('snapshot');
      }
    });
  }
  async function preferences(autoCheck: boolean, downloadPolicy: string) {
    if (preferencesPending) return;
    preferencesPending = true;
    try {
      await action('preferences', { autoCheck, downloadPolicy });
    } finally {
      preferencesPending = false;
    }
  }
  onMount(() => {
    void action('snapshot');
    const poll = setInterval(() => {
      if (polling) return;
      polling = true;
      void action('snapshot').finally(() => {
        polling = false;
      });
    }, 1500);
    return () => {
      clearInterval(poll);
    };
  });
</script>

<section class="updates-page" aria-label={zh ? '软件更新' : 'Software updates'}>
  <div class="update-overview">
    <span class="update-symbol"><Icon name="download" size={24} /></span>
    <div class="update-identity">
      <h3>DJHX Memo <span>v{status?.current_version ?? '—'}</span></h3>
      <p>
        {managed
          ? zh
            ? 'Linux · 系统软件包管理'
            : 'Linux · System packages'
          : zh
            ? 'Windows · 稳定版'
            : 'Windows · Stable'}
      </p>
    </div>
    <span
      class="update-state"
      class:current={phase === 'up_to_date' || phase === 'completed'}
      aria-live="polite"
      >{managed ? (zh ? '手动更新' : 'Manual updates') : label(names[phase])}</span
    >
  </div>
  {#if status?.last_checked && !managed}<p class="update-last">
      {zh ? '上次检查' : 'Last checked'} · {formatTime(
        new Date(status.last_checked * 1000).toISOString(),
        language,
        timeZone,
        '',
      )}
    </p>{/if}
  {#if managed}
    <div class="update-card">
      <h4>{zh ? '通过 DEB 安装包更新' : 'Update with the DEB package'}</h4>
      <p class="update-muted">
        {zh
          ? '从官网下载适合本机的 DEB，覆盖安装即可保留原有笔记。'
          : 'Download the DEB from the website and install it over this version to keep your notes.'}
      </p>
    </div>
  {:else}
    <div class="update-card">
      {#if status?.candidate}<div class="release-heading">
          <h4>
            {zh ? '可用版本' : 'Available version'} <strong>v{status.candidate.version}</strong>
          </h4>
          <span>{(status.candidate.size / 1048576).toFixed(1)} MiB</span>
        </div>
        <p class="release-notes">{status.candidate.notes}</p>
      {:else}<h4>
          {phase === 'up_to_date'
            ? zh
              ? '你正在使用最新版本'
              : 'You’re up to date'
            : zh
              ? '保持更新'
              : 'Keep up to date'}
        </h4>{/if}
      {#if phase === 'downloading' && status?.candidate}<div class="update-progress">
          <progress max={status.candidate.size} value={status.downloaded}></progress><span
            >{(status.downloaded / 1048576).toFixed(1)} / {(
              status.candidate.size / 1048576
            ).toFixed(1)} MiB</span
          >
        </div>{/if}
      {#if errorCode}<div class="update-error" role="alert">
          <Icon name="circle-alert" size={18} /><span>{label(errors[errorCode])}</span>
        </div>{/if}
      <div class="update-actions">
        {#if phase !== 'downloading'}<button
            class="primary-button"
            disabled={busy || !status?.target}
            on:click={primaryAction}
            ><Icon
              name={phase === 'available'
                ? 'download'
                : phase === 'ready'
                  ? 'rotate-ccw'
                  : 'refresh-cw'}
              size={17}
            />{phase === 'ready'
              ? zh
                ? '安装并重启'
                : 'Install and restart'
              : phase === 'available'
                ? zh
                  ? '下载更新'
                  : 'Download update'
                : zh
                  ? '检查更新'
                  : 'Check for updates'}</button
          >
        {:else}<button class="secondary-button" on:click={() => action('cancel')}
            >{zh ? '取消下载' : 'Cancel download'}</button
          >{/if}
        {#if status?.candidate && !busy}<button class="text-button" on:click={() => action('defer')}
            >{zh ? '稍后' : 'Later'}</button
          >{/if}
      </div>
    </div>
    {#if status}<div class="update-card update-preferences">
        <h4>{zh ? '自动更新设置' : 'Update preferences'}</h4>
        <label class="update-preference"
          ><span>{zh ? '自动检查更新' : 'Check automatically'}</span><input
            type="checkbox"
            role="switch"
            checked={status.auto_check}
            disabled={busy || preferencesPending}
            on:change={(e) =>
              preferences(e.currentTarget.checked, status?.download_policy ?? 'unmetered')}
          /></label
        >
        <label class="update-preference"
          ><span>{zh ? '自动下载' : 'Automatic downloads'}</span><select
            value={status.download_policy}
            disabled={busy || preferencesPending || !status.auto_check}
            on:change={(e) => preferences(status?.auto_check ?? true, e.currentTarget.value)}
            ><option value="notify">{zh ? '只提醒' : 'Notify only'}</option><option
              value="unmetered">{zh ? '仅非计费网络' : 'Unmetered networks'}</option
            ><option value="any">{zh ? '允许计费网络' : 'Any network'}</option></select
          ></label
        >
      </div>{/if}
  {/if}
</section>

<style>
  .updates-page {
    display: grid;
    gap: 16px;
    padding-top: 8px;
    max-width: 640px;
  }
  .update-overview {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 4px 0;
    flex-wrap: wrap;
  }
  .update-symbol {
    display: grid;
    place-items: center;
    width: 48px;
    height: 48px;
    border-radius: 14px;
    background: var(--selected);
    color: var(--accent);
    flex: none;
  }
  .update-identity {
    flex: 1;
    min-width: 180px;
  }
  .update-identity h3 {
    margin: 0;
    font-size: 17px;
  }
  .update-identity h3 span {
    font-size: 13px;
    color: var(--muted);
    font-weight: 400;
    margin-left: 6px;
  }
  .update-identity p,
  .update-last {
    margin: 5px 0 0;
    color: var(--muted);
    font-size: 12px;
  }
  .update-state {
    font-size: 12px;
    color: var(--muted);
    background: var(--hover);
    padding: 6px 10px;
    border-radius: 20px;
  }
  .update-state.current {
    background: var(--selected);
    color: var(--accent);
  }
  .update-card {
    padding: 20px;
    border: 1px solid var(--border);
    border-radius: 14px;
    background: var(--surface);
  }
  h4 {
    font-size: 14px;
    margin: 0;
    font-weight: 600;
  }
  .release-heading {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    justify-content: space-between;
    gap: 8px;
  }
  .release-heading strong {
    color: var(--accent);
    margin-left: 6px;
  }
  .release-heading > span,
  .update-muted {
    color: var(--muted);
    font-size: 12px;
  }
  .release-notes {
    white-space: pre-wrap;
    font-size: 13px;
    line-height: 1.75;
    margin: 14px 0 0;
    max-height: 192px;
    overflow: auto;
  }
  .update-error {
    display: flex;
    align-items: flex-start;
    gap: 9px;
    padding: 12px;
    background: var(--error-surface);
    color: var(--danger);
    border-radius: 9px;
    margin-top: 16px;
    font-size: 13px;
  }
  .update-error :global(svg) {
    flex: none;
    margin-top: 1px;
  }
  .update-actions {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
    margin-top: 20px;
  }
  .update-actions button {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    min-height: 38px;
  }
  .update-preferences h4 {
    margin-bottom: 8px;
  }
  .update-preference {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 20px;
    min-height: 52px;
    font-size: 13px;
  }
  .update-preference + .update-preference {
    border-top: 1px solid var(--border);
  }
  select {
    max-width: 58%;
    padding: 8px 28px 8px 10px;
    color: var(--ink);
    background: var(--canvas);
    border: 1px solid var(--border);
    border-radius: 8px;
    font: inherit;
  }
  input[role='switch'] {
    accent-color: var(--accent);
    width: 18px;
    height: 18px;
  }
  .update-progress {
    margin-top: 18px;
    display: grid;
    gap: 8px;
  }
  progress {
    width: 100%;
    height: 6px;
    accent-color: var(--accent);
  }
  .update-progress span {
    color: var(--muted);
    font-size: 12px;
    text-align: right;
  }
  @media (max-width: 500px) {
    .update-card {
      padding: 16px;
    }
    .update-overview {
      gap: 10px;
    }
  }
</style>
