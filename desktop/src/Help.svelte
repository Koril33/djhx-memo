<script lang="ts">
  import { onMount } from 'svelte';
  import Icon from './Icon.svelte';

  export let id: string;
  export let heading: string;
  export let label: string;

  let open = false;
  let root: HTMLElement;
  let trigger: HTMLButtonElement;

  onMount(() => {
    const closeAll = () => {
      open = false;
    };
    // Esc with focus inside this disclosure closes it and returns focus to its
    // trigger; the dialog's focus trap dispatches this instead of closing itself.
    const onEsc = () => {
      if (open && root?.contains(document.activeElement)) {
        open = false;
        trigger?.focus();
      }
    };
    window.addEventListener('djhx-close-help', closeAll);
    window.addEventListener('djhx-esc-help', onEsc);
    return () => {
      window.removeEventListener('djhx-close-help', closeAll);
      window.removeEventListener('djhx-esc-help', onEsc);
    };
  });
</script>

<div class="help" data-help class:open bind:this={root}>
  <div class="help-row">
    <span id={`${id}-label`} class="help-heading">{heading}</span>
    <button
      type="button"
      class="help-trigger"
      bind:this={trigger}
      aria-label={`${label}: ${heading}`}
      title={`${label}: ${heading}`}
      aria-expanded={open}
      aria-controls={`${id}-text`}
      on:click={() => (open = !open)}
    >
      <Icon name="info" size={16} />
    </button>
  </div>
  <!-- svelte-ignore a11y_no_noninteractive_tabindex (Focusable prose preserves selection and provides keyboard access to the scrollable description.) -->
  <div
    id={`${id}-text`}
    class="help-body"
    role="region"
    aria-label={heading}
    tabindex="0"
    hidden={!open}
  >
    <slot />
  </div>
</div>

<style>
  .help {
    min-width: 0;
    margin-bottom: 12px;
  }
  .help-row {
    display: flex;
    align-items: center;
    gap: 4px;
    min-height: 36px;
  }
  .help-heading {
    font-weight: 550;
    min-width: 0;
    overflow-wrap: anywhere;
  }
  .help-trigger {
    width: 36px;
    height: 36px;
    padding: 0;
    display: inline-grid;
    place-items: center;
    border-radius: 50%;
    color: var(--muted);
    flex-shrink: 0;
    line-height: 1;
  }
  .help.open .help-trigger {
    color: var(--accent);
    background: var(--selected);
  }
  .help-body {
    user-select: text;
    -webkit-user-select: text;
    cursor: text;
    margin-top: 8px;
    padding: 10px 12px;
    background: var(--canvas);
    border: 1px solid var(--border);
    border-radius: 8px;
    color: var(--muted);
    font-size: 12px;
    font-weight: normal;
    line-height: 1.75;
    overflow-wrap: anywhere;
  }
  .help-body[hidden] {
    display: none !important;
  }
</style>
