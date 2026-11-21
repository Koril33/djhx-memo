<script lang="ts">
  import { onDestroy, tick } from 'svelte';
  import { call } from './lib/bridge';
  import Icon from './Icon.svelte';
  import { createTranslator, type Language } from './lib/i18n';
  import { formatTime, searchSourceLabel, type Preferences } from './lib/preferences';
  import type { Profile, SearchHit, SearchResult } from './lib/types';

  export let language: Language = 'en';
  export let timeZone: Preferences['timeZone'] = 'system';
  export let profileId: string = 'local';
  export let profiles: Profile[] = [];
  export let onClose: () => void = () => {};
  export let onOpenNote: (hit: SearchHit, query: string) => void | Promise<void> = () => {};

  $: t = createTranslator(language);
  $: selectedIsAccount = profiles.some((p) => p.id === profileId && p.kind === 'account');

  let query = '';
  let hits: SearchHit[] = [];
  let truncated = false;
  let selectedIndex = 0;
  let failure: unknown = null;
  let searching = false;
  let searchTimer: ReturnType<typeof setTimeout> | undefined;
  let requestCounter = 0;
  // Request-time snapshot used to label sources; it is not re-read from live props
  // after a response so a changed registry cannot relabel results by note ID.
  let scopeProfiles: Profile[] = [];

  function time(value: string): string {
    return formatTime(value, language, timeZone, '');
  }
  function sourceLabel(hit: SearchHit): string {
    const profile = scopeProfiles.find((p) => p.id === hit.profile_id);
    return searchSourceLabel(profile, hit.space_kind, t);
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
    const scopeId = profileId;
    const snapshot = profiles;
    if (!term) {
      if (request === requestCounter) {
        hits = [];
        truncated = false;
        failure = null;
        searching = false;
        selectedIndex = 0;
        scopeProfiles = snapshot;
      }
      return;
    }
    searching = true;
    try {
      const result = await call<SearchResult>({
        action: 'search_notes',
        profile_id: scopeId,
        query: term,
        scope: { kind: 'local_and_current_account', include_deleted: true },
      });
      if (request !== requestCounter) return;
      hits = result.hits;
      truncated = result.truncated;
      failure = null;
      selectedIndex = 0;
      scopeProfiles = snapshot;
    } catch (error) {
      if (request !== requestCounter) return;
      hits = [];
      truncated = false;
      failure = error;
      selectedIndex = 0;
    } finally {
      if (request === requestCounter) searching = false;
    }
  }
  function onQueryInput(): void {
    selectedIndex = 0;
    scheduleSearch();
  }
  function scrollSelectedIntoView(): void {
    void tick().then(() => {
      document
        .querySelector<HTMLElement>('.global-search-result[aria-current="true"]')
        ?.scrollIntoView({ block: 'nearest' });
    });
  }
  function onKeydown(event: KeyboardEvent): void {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      if (hits.length) {
        selectedIndex = Math.min(selectedIndex + 1, hits.length - 1);
        scrollSelectedIntoView();
      }
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      if (hits.length) {
        selectedIndex = Math.max(selectedIndex - 1, 0);
        scrollSelectedIntoView();
      }
    } else if (event.key === 'Enter') {
      if (event.isComposing || event.keyCode === 229) return;
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
  onDestroy(() => {
    clearTimeout(searchTimer);
    requestCounter++;
  });
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
  <p class="global-search-hint">
    {selectedIsAccount ? t('searchScopeHintAccount') : t('searchScopeHintLocal')}
  </p>
  {#if searching}<p class="global-search-hint global-search-loading">{t('searchLoading')}</p>{/if}
  {#if truncated}
    <p class="global-search-hint global-search-truncated">{t('searchTruncated')}</p>
  {/if}
  <div class="global-search-results">
    {#if query}
      {#if hits.length}
        {#each hits as hit, index (`${hit.profile_id}:${hit.note_id}`)}
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
              <span class="global-search-badge">{sourceLabel(hit)}</span>
              {#if hit.deleted}<span class="global-search-badge global-search-trash-badge"
                  >{t('trash')}</span
                >{/if}
              <span>{t('matches', { count: hit.hit_count })}</span>
              <time>{time(hit.updated_at)}</time>
            </span>
          </button>
        {/each}
      {:else if failure}
        <div class="global-search-empty global-search-error">
          <span>{t('searchFailed')}</span>
          <button class="global-search-retry" on:click={() => void runSearch()}>{t('retry')}</button
          >
        </div>
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
    min-height: 0;
    flex: 1 1 auto;
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
    flex-shrink: 0;
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
  .global-search-hint {
    margin: 0;
    font-size: 11px;
    color: var(--muted);
    line-height: 1.6;
    flex-shrink: 0;
  }
  .global-search-truncated {
    color: var(--warning);
  }
  .global-search-loading {
    color: var(--accent);
  }
  .global-search-results {
    overflow-y: auto;
    min-height: 0;
    flex: 1 1 auto;
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
    /* A vertical flex child must not shrink below its natural content height. */
    flex: 0 0 auto;
  }
  .global-search-result.selected {
    background: var(--selected);
    border-color: color-mix(in srgb, var(--accent) 14%, transparent);
  }
  .global-search-title {
    font-size: 13px;
    font-weight: 550;
    overflow-wrap: anywhere;
    display: -webkit-box;
    line-clamp: 1;
    -webkit-line-clamp: 1;
    -webkit-box-orient: vertical;
    overflow: hidden;
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
    flex-wrap: wrap;
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
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .global-search-trash-badge {
    color: var(--warning);
    border-color: color-mix(in srgb, var(--warning) 35%, transparent);
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
  .global-search-error {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 12px;
  }
  .global-search-retry {
    padding: 6px 14px;
    border: 1px solid var(--border);
    background: var(--canvas);
    border-radius: 6px;
    color: var(--accent);
    font-size: 12px;
  }
</style>
