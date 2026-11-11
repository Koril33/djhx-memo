<script lang="ts">
  import { onMount } from 'svelte';
  import { native } from './lib/bridge';
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
  };
  let status: Status | null = null;
  let installing = false;
  let preferencesPending = false;
  let installError = '';
  let polling = false;
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
  $: busy = installing || ['checking', 'downloading', 'preparing'].includes(status?.phase ?? '');
  function label(pair: [string, string] | undefined) {
    return pair?.[zh ? 0 : 1] ?? (zh ? '更新未完成，请重试。' : 'Update failed. Try again.');
  }
  async function action(name: string, fields: Record<string, unknown> = {}) {
    try {
      status = JSON.parse(await native<string>('update_invoke', { action: name, ...fields }));
    } catch {
      installError = 'UPDATE_INSTALL';
    }
  }
  async function apply() {
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

<section aria-label={zh ? '软件更新' : 'Software updates'}>
  <h3>{zh ? '软件更新' : 'Software updates'}</h3>
  <p>
    {status?.current_version ?? ''} · {status?.target === 'windows-x86_64-nsis'
      ? zh
        ? 'Windows 安装版'
        : 'Windows installer'
      : zh
        ? '系统管理的安装'
        : 'System-managed installation'}
  </p>
  <p aria-live="polite">{label(names[status?.phase ?? 'idle'])}</p>
  {#if status?.last_checked}<p>
      {zh ? '上次检查：' : 'Last checked: '}{formatTime(
        new Date(status.last_checked * 1000).toISOString(),
        language,
        timeZone,
        '',
      )}
    </p>{/if}
  {#if status?.candidate}
    <p>{status.candidate.version} · {(status.candidate.size / 1048576).toFixed(1)} MiB</p>
    <pre class="update-notes">{status.candidate.notes}</pre>
    {#if status.phase === 'downloading'}<progress
        max={status.candidate.size}
        value={status.downloaded}
      ></progress>{/if}
  {/if}
  {#if status?.error && status.error !== 'UPDATE_CANCELED'}<p class="inline-error">
      {label(errors[status.error])}
    </p>{/if}
  {#if installError}<p class="inline-error">{label(errors[installError])}</p>{/if}
  <div class="update-actions">
    <button disabled={busy} on:click={() => action('check')}
      >{zh ? '检查更新' : 'Check for updates'}</button
    >
    {#if status?.phase === 'ready'}<button disabled={busy} on:click={apply}
        >{zh ? '保存并重启更新' : 'Save and restart to update'}</button
      >
    {:else if status?.candidate && status.phase !== 'downloading'}<button
        disabled={busy}
        on:click={() => action('download')}>{zh ? '下载更新' : 'Download update'}</button
      >{/if}
    {#if status?.phase === 'downloading'}<button on:click={() => action('cancel')}
        >{zh ? '取消下载' : 'Cancel download'}</button
      >{/if}
    {#if status?.candidate}<button disabled={busy} on:click={() => action('defer')}
        >{zh ? '稍后' : 'Later'}</button
      >{/if}
  </div>
  {#if status}
    <label
      ><input
        type="checkbox"
        checked={status.auto_check}
        disabled={installing || preferencesPending}
        on:change={(e) =>
          preferences(e.currentTarget.checked, status?.download_policy ?? 'unmetered')}
      />{zh ? '自动检查更新' : 'Check automatically'}</label
    >
    <label
      >{zh ? '自动下载' : 'Automatic downloads'}
      <select
        value={status.download_policy}
        disabled={installing || preferencesPending || !status.auto_check}
        on:change={(e) => preferences(status?.auto_check ?? true, e.currentTarget.value)}
      >
        <option value="notify">{zh ? '只提醒' : 'Notify only'}</option>
        <option value="unmetered"
          >{zh ? '仅非计费网络（无法判断时只提醒）' : 'Unmetered only (notify if unknown)'}</option
        >
        <option value="any">{zh ? '允许计费网络' : 'Allow metered networks'}</option>
      </select>
    </label>
  {/if}
  <p>
    {zh
      ? '无需登录。更新检查不发送笔记或账号凭据。安装前会保存本地内容。'
      : 'No sign-in needed. Checks send no notes or account credentials. Notes are saved before installation.'}
  </p>
</section>

<style>
  section {
    padding-top: 0.25rem;
  }
  label {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    margin: 0.75rem 0;
  }
  .update-actions {
    display: flex;
    gap: 0.5rem;
    flex-wrap: wrap;
  }
  .update-notes {
    white-space: pre-wrap;
    font: inherit;
    max-height: 12rem;
    overflow: auto;
  }
  progress {
    width: 100%;
  }
</style>
