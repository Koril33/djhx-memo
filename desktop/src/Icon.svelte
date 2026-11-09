<script lang="ts">
  import { icons, type IconName } from './lib/icons';

  export let name: IconName;
  export let size = 20;
</script>

<svg
  xmlns="http://www.w3.org/2000/svg"
  width={size}
  height={size}
  viewBox="0 0 24 24"
  fill="none"
  stroke="currentColor"
  stroke-width="2"
  stroke-linecap="round"
  stroke-linejoin="round"
  aria-hidden="true"
  focusable="false"
>
  {#each icons[name] as [tag, attributes], index (name + ':' + index)}
    <svelte:element this={tag} {...attributes} />
  {/each}
</svg>
