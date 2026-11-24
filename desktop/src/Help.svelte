<script lang="ts">
  import Icon from './Icon.svelte';
  export let id: string;
  export let heading: string;
  export let label: string;
  let open = false;
</script>

<details class="help" data-menu bind:open>
  <!-- svelte-ignore a11y_no_redundant_roles (WebKitGTK needs an explicit accessible role.) -->
  <summary
    role="button"
    aria-label={`${label}: ${heading}`}
    title={`${label}: ${heading}`}
    aria-expanded={open}
    aria-controls={`${id}-text`}
    aria-describedby={`${id}-text`}
  >
    <span id={`${id}-label`}>{heading}</span>
    <span class="help-trigger"><Icon name="info" size={16} /></span>
  </summary>
  <div id={`${id}-text`} class="help-body"><slot /></div>
</details>

<style>
  .help {
    min-width: 0;
    margin-bottom: 12px;
  }
  summary {
    display: flex;
    align-items: center;
    gap: 4px;
    min-height: 36px;
    cursor: pointer;
    list-style: none;
    font-weight: 550;
  }
  summary::-webkit-details-marker {
    display: none;
  }
  summary::marker {
    content: '';
  }
  .help-trigger {
    display: inline-grid;
    place-items: center;
    width: 36px;
    height: 36px;
    flex-shrink: 0;
    border-radius: 50%;
    color: var(--muted);
  }
  .help[open] .help-trigger {
    color: var(--accent);
    background: var(--selected);
  }
  .help-body {
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
</style>
