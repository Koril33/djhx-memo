<script lang="ts">
  import Icon from './Icon.svelte';
  export let label: string;
</script>

<details class="help" data-menu>
  <!-- svelte-ignore a11y_no_redundant_roles (WebKitGTK exposes an unknown AT-SPI role without this explicit role.) -->
  <summary class="help-trigger" role="button" aria-label={label} title={label}>
    <Icon name="info" size={14} />
  </summary>
  <div class="help-body"><slot /></div>
</details>

<style>
  .help {
    display: inline-block;
    vertical-align: middle;
    margin-left: 6px;
  }
  .help-trigger {
    display: inline-grid;
    place-items: center;
    width: 20px;
    height: 20px;
    border-radius: 50%;
    color: var(--muted);
    cursor: pointer;
    list-style: none;
  }
  .help-trigger::-webkit-details-marker {
    display: none;
  }
  .help-trigger::marker {
    content: '';
  }
  .help[open] .help-trigger {
    color: var(--accent);
    background: var(--selected);
  }
  .help-body {
    display: block;
    margin: 8px 0 0;
    padding: 10px 12px;
    max-width: 440px;
    background: var(--canvas);
    border: 1px solid var(--border);
    border-radius: 8px;
    color: var(--muted);
    font-size: 12px;
    line-height: 1.75;
  }
</style>
