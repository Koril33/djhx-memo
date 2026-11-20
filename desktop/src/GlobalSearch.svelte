<script lang="ts">
  import { call } from './lib/bridge';
  import Icon from './Icon.svelte';
  import { createTranslator, type Language } from './lib/i18n';
  import { formatTime, type Preferences } from './lib/preferences';
  import type { SearchHit, SearchResult } from './lib/types';

  export let language: Language = 'en';
  export let timeZone: Preferences['timeZone'] = 'system';
  export let onClose: () => void = () => {};
  export let onOpenNote: (hit: SearchHit, query: string) => void | Promise<void> = () => {};

  $: t = createTranslator(language);

  type ScopeKind = 'current' | 'all_local' | 'account';
  const scopeOptions: {
    kind: ScopeKind;
    labelKey: 'searchCurrentSpace' | 'searchAllLocal' | 'searchAccount';
  }[] = [
    { kind: 'current', labelKey: 'searchCurrentSpace' },
    { kind: 'all_local', labelKey: 'searchAllLocal' },
    { kind: 'account', labelKey: 'searchAccount' },
  ];

  let query = '';
  let scopeKind: ScopeKind = 'current';
  let includeDeleted = false;
  let hits: SearchHit[] = [];
  let truncated = false;
  let selectedIndex = 0;
  let failure: unknown = null;
  let searchTimer: ReturnType<typeof setTimeout> | undefined;
  let requestCounter = 0;

  function time(value: string): string {
    return formatTime(value, language, timeZone, '');
  }
  function scheduleSearch(): void {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      void runSearch();
    }, 150);
  }
  async function runSearch(): Promise<void> {
    const request = ++requestCounter;
    const term = query;
    if (!term) {
      if (request === requestCounter) {
        hits = [];
        truncated = false;
        failure = null;
      }
      return;
    }
    try {
      const result = await call<SearchResult>({
        action: 'search_notes',
        query: term,
        scope: { kind: scopeKind, include_deleted: includeDeleted },
      });
      if (request !== requestCounter) return;
      hits = result.hits;
      truncated = result.truncated;
      selectedIndex = 0;
      failure = null;
    } catch (error) {
      if (request !== requestCounter) return;
      hits = [];
      truncated = false;
      failure = error;
    }
  }
  function selectScope(kind: ScopeKind): void {
    if (scopeKind === kind) return;
    scopeKind = kind;
    scheduleSearch();
  }
  function toggleTrash(): void {
    includeDeleted = !includeDeleted;
    scheduleSearch();
  }
  function onQueryInput(): void {
    selectedIndex = 0;
    scheduleSearch();
  }
  function onKeydown(event: KeyboardEvent): void {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      if (hits.length) selectedIndex = Math.min(selectedIndex + 1, hits.length - 1);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      selectedIndex = Math.max(selectedIndex - 1, 0);
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const hit = hits[selectedIndex];
      if (hit) void openNote(hit);
    }
  }
  async function openNote(hit: SearchHit): Promise<void> {
    const term = query;
    onClose();
    try {
      await onOpenNote(hit, term);
    } catch {
      // The parent surfaces failures through its own error channel.
    }
  }
  function spaceBadge(hit: SearchHit): string {
    if (hit.deleted) return t('trash');
    return hit.space_kind === 'account' ? t('accountSpace') : t('localSpace');
  }
</script>

<div class="global-search">
  <div class="global-search-input">
    <Icon name="search" size={18} />
    <input
      bind:value={query}
      on:input={onQueryInput}
      on:keydown={onKeydown}
      placeholder={t('searchPlaceholder')}
      aria-label={t('globalSearch')}
      autocomplete="off"
      spellcheck="false"
    />
  </div>
  <div class="global-search-scope" role="group" aria-label={t('searchScope')}>
    {#each scopeOptions as option (option.kind)}
      <button
        class:selected={scopeKind === option.kind}
        aria-pressed={scopeKind === option.kind}
        on:click={() => selectScope(option.kind)}>{t(option.labelKey)}</button
      >
    {/each}
    <label class="global-search-trash">
      <input type="checkbox" checked={includeDeleted} on:change={toggleTrash} />{t(
        'includeTrash',
      )}</label
    >
  </div>
  {#if scopeKind === 'account'}
    <p class="global-search-hint">{t('searchLocalOnlyHint')}</p>
  {/if}
  {#if truncated}
    <p class="global-search-hint global-search-truncated">{t('searchTruncated')}</p>
  {/if}
  <div class="global-search-results">
    {#if query}
      {#if hits.length}
        {#each hits as hit, index (hit.note_id + ':' + hit.profile_id)}
          <button
            class="global-search-result"
            class:selected={index === selectedIndex}
            aria-current={index === selectedIndex ? 'true' : undefined}
            on:click={() => void openNote(hit)}
            on:mouseenter={() => (selectedIndex = index)}
          >
            <span class="global-search-title">
              {#each hit.title_segments as segment, i (i)}
                {#if segment.hit}<mark>{segment.text}</mark>{:else}{segment.text}{/if}
              {/each}
            </span>
            {#if hit.snippet}<span class="global-search-snippet">
                {#each hit.segments as segment, i (i)}
                  {#if segment.hit}<mark>{segment.text}</mark>{:else}{segment.text}{/if}
                {/each}
              </span>{/if}
            <span class="global-search-meta">
              <span class="global-search-badge">{spaceBadge(hit)}</span>
              <span>{t('matches', { count: hit.hit_count })}</span>
              <time>{time(hit.updated_at)}</time>
            </span>
          </button>
        {/each}
      {:else if failure}
        <div class="global-search-empty">{t('genericError')}</div>
      {:else}
        <div class="global-search-empty">{t('searchNoResults')}</div>
      {/if}
    {:else}
      <div class="global-search-empty">{t('searchEmptyQuery')}</div>
    {/if}
  </div>
</div>

<style>
  .global-search {
    display: flex;
    flex-direction: column;
    gap: 12px;
    padding: 16px;
    overflow: hidden;
  }
  .global-search-input {
    display: flex;
    align-items: center;
    gap: 10px;
    color: var(--muted);
    border: 1px solid var(--border);
    background: var(--surface);
    border-radius: 8px;
    padding: 0 12px;
    height: 44px;
  }
  .global-search-input:focus-within {
    border-color: var(--accent);
  }
  .global-search-input input {
    flex: 1;
    border: 0;
    background: none;
    outline: none;
    font-size: 14px;
    height: 100%;
    color: var(--ink);
  }
  .global-search-scope {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
  }
  .global-search-scope button {
    min-height: 30px;
    padding: 0 12px;
    font-size: 12px;
    color: var(--muted);
    border: 1px solid var(--border);
    background: var(--canvas);
    border-radius: 999px;
  }
  .global-search-scope button.selected {
    color: var(--accent);
    border-color: var(--accent);
    background: var(--selected);
  }
  .global-search-trash {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    margin-left: auto;
    font-size: 12px;
    color: var(--muted);
    white-space: nowrap;
  }
  .global-search-hint {
    margin: 0;
    font-size: 11px;
    color: var(--muted);
    line-height: 1.6;
  }
  .global-search-truncated {
    color: var(--warning);
  }
  .global-search-results {
    overflow-y: auto;
    max-height: 380px;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .global-search-result {
    display: flex;
    flex-direction: column;
    gap: 6px;
    width: 100%;
    text-align: left;
    padding: 12px;
    border: 1px solid transparent;
    border-radius: 8px;
  }
  .global-search-result.selected {
    background: var(--selected);
    border-color: color-mix(in srgb, var(--accent) 14%, transparent);
  }
  .global-search-title {
    font-size: 13px;
    font-weight: 550;
    overflow-wrap: anywhere;
  }
  .global-search-snippet {
    font-size: 12px;
    line-height: 1.6;
    color: var(--muted);
    overflow-wrap: anywhere;
    display: -webkit-box;
    line-clamp: 2;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .global-search-meta {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 11px;
    color: var(--muted);
  }
  .global-search-badge {
    padding: 1px 8px;
    border-radius: 999px;
    background: var(--canvas);
    border: 1px solid var(--border);
    color: var(--muted);
  }
  .global-search-result mark {
    background: color-mix(in srgb, var(--accent) 26%, transparent);
    color: var(--ink);
    border-radius: 3px;
    padding: 0 1px;
  }
  .global-search-empty {
    padding: 40px 16px;
    text-align: center;
    color: var(--muted);
    font-size: 13px;
  }
</style>
