<script lang="ts">
  import type { TurnResponse } from '#lib/types';
  import { segments } from '#lib/highlight';
  import Corrections from './Corrections.svelte';

  let { turn, onReplay }: { turn: TurnResponse; onReplay?: () => void } = $props();
  const parts = $derived(segments(turn.transcript, turn.corrections.map((c) => c.wrong)));
</script>

<article class="flex flex-col gap-3">
  <div class="card ml-8 bg-brand-700/5">
    <p class="text-xs font-semibold uppercase tracking-wide text-slate-500">Du</p>
    <p class="text-lg">
      {#each parts as p, i (i)}{#if p.wrong}<mark class="rounded bg-amber-200 px-0.5 text-slate-900 dark:bg-amber-500/40 dark:text-amber-50">{p.text}</mark>{:else}{p.text}{/if}{/each}
    </p>
    {#if turn.corrections.length === 0}
      <p class="mt-2 text-sm font-medium text-emerald-700 dark:text-emerald-400">✅ Fehlerfrei – stark!</p>
    {:else}
      <Corrections corrections={turn.corrections} />
    {/if}
    {#if turn.natural && turn.natural !== turn.transcript}
      <p class="mt-3 text-sm"><span aria-hidden="true">💬</span> So klingt es natürlich: <em>{turn.natural}</em></p>
    {/if}
  </div>
  <div class="card mr-8">
    <p class="flex items-center justify-between text-xs font-semibold uppercase tracking-wide text-slate-500">
      Redefluss
      {#if turn.replyAudio && onReplay}<button type="button" class="text-brand-700" onclick={onReplay} aria-label="Antwort noch einmal anhören">🔊</button>{/if}
    </p>
    <p class="text-lg">{turn.reply}</p>
  </div>
</article>
