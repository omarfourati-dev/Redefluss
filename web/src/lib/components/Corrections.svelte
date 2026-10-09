<script lang="ts">
  import type { Correction } from '#lib/types';
  import { CATEGORY_LABEL } from '#lib/categories';

  let { corrections }: { corrections: Correction[] } = $props();
</script>

{#if corrections.length > 0}
  <ul class="mt-3 flex flex-col gap-2 text-sm">
    {#each corrections as c, i (i)}
      <li>
        ✏️ <span class="line-through decoration-red-500">{c.wrong}</span> → <strong>{c.right}</strong>
        <span class="ml-1 rounded bg-slate-100 px-1.5 py-0.5 text-xs text-slate-600 dark:bg-slate-800 dark:text-slate-300">{CATEGORY_LABEL[c.category] ?? c.category}</span>
        <span class="block text-slate-600 dark:text-slate-400">{c.rule}</span>
      </li>
    {/each}
  </ul>
{/if}
