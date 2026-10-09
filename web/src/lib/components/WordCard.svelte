<script lang="ts">
  import { apiBlob } from '#lib/api';
  import { ARTICLE_CLASS } from '#lib/articles';
  import type { Player } from '#lib/talk.svelte';
  import type { Card } from '#lib/types';

  /** `review`: only article, word and meaning – the example (also in the audio) would give the answer away. */
  let { card, player, review = false }: { card: Card; player: Player; review?: boolean } = $props();

  let loading = $state(false);
  let error = $state('');

  async function listen() {
    if (loading) return;
    player.unlock(); // inside the click, before the await
    loading = true;
    error = '';
    try {
      player.play(await apiBlob(`/api/vocab/cards/${card.id}/audio`));
    } catch (e) {
      error = (e as Error).message;
    } finally {
      loading = false;
    }
  }
</script>

<article class="card">
  <div class="flex items-start justify-between gap-3">
    <p class="text-2xl font-bold">
      {#if card.article}<span class={ARTICLE_CLASS[card.article] ?? ''}>{card.article}</span>{' '}{/if}<span>{card.word}</span>
    </p>
    {#if !review}
      <button type="button" class="text-2xl text-brand-700 disabled:opacity-50" disabled={loading}
        aria-label="{card.word} anhören" onclick={listen}>🔊</button>
    {/if}
  </div>
  {#if !review && card.plural}<p class="text-sm text-slate-500">Plural: {card.plural}</p>{/if}
  <p class="mt-2">{card.meaning}</p>
  {#if !review && card.example}<p class="mt-2 text-slate-600 dark:text-slate-400"><em>{card.example}</em></p>{/if}
  {#if error}<p role="alert" class="mt-2 rounded-lg bg-red-50 p-2 text-sm text-red-800 dark:bg-red-950 dark:text-red-200">{error}</p>{/if}
</article>
