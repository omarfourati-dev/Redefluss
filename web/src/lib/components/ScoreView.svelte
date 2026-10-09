<script lang="ts">
  import type { Assessment, WordScore } from '#lib/types';

  let { text, assessment, onSpeakWord, onAgain }:
    { text: string; assessment: Assessment; onSpeakWord: (word: string) => void; onAgain: () => void } = $props();

  const EDGE = /^[\s.,!?;:"'„“‚‘»«()\-–]+|[\s.,!?;:"'„“‚‘»«()\-–]+$/g;
  const norm = (s: string) => s.replace(EDGE, '').toLocaleLowerCase('de');

  interface Token { text: string; word: WordScore | null }

  /** The reference sentence token by token (keeps punctuation), each matched in order to its scored word. */
  const tokens = $derived.by<Token[]>(() => {
    const words = assessment.words.filter((w) => w.errorType !== 'Insertion');
    let next = 0;
    return text.split(/\s+/).filter(Boolean).map((t) => {
      const key = norm(t);
      if (!key) return { text: t, word: null };
      for (let k = next; k < words.length; k++) {
        if (norm(words[k].word) === key) {
          next = k + 1;
          return { text: t, word: words[k] };
        }
      }
      return { text: t, word: null };
    });
  });

  const scores = $derived([
    { label: 'Gesamt', value: assessment.pronunciation },
    { label: 'Genauigkeit', value: assessment.accuracy },
    { label: 'Flüssigkeit', value: assessment.fluency },
    { label: 'Vollständigkeit', value: assessment.completeness }
  ]);

  let open = $state<number | null>(null);
  const selected = $derived(open === null ? null : (tokens[open]?.word ?? null));

  const omitted = (w: WordScore) => w.errorType === 'Omission';
  const round = (n: number) => Math.round(Math.max(0, Math.min(100, n)));

  /** Full class names so Tailwind finds them: ≥ 80 green, 60–79 amber, < 60 red. */
  function scoreClass(score: number): string {
    if (score >= 80) return 'text-green-700 dark:text-green-400';
    if (score >= 60) return 'text-amber-600 dark:text-amber-400';
    return 'text-red-700 dark:text-red-400';
  }

  function toggle(i: number) {
    open = open === i ? null : i;
  }
</script>

<section class="card flex flex-col gap-4" aria-label="Bewertung">
  <div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
    {#each scores as s (s.label)}
      <div class="text-center">
        <p class="text-sm text-slate-500">{s.label}</p>
        <p class="text-2xl font-bold {scoreClass(s.value)}">{round(s.value)}</p>
      </div>
    {/each}
  </div>

  <p class="text-2xl leading-relaxed">
    {#each tokens as t, i (i)}{#if i > 0}{' '}{/if}{#if t.word && omitted(t.word)}<span class="text-slate-400 line-through">{t.text}</span><span class="sr-only"> (fehlte)</span>{:else if t.word}<button type="button" class="rounded px-0.5 underline decoration-dotted underline-offset-4 hover:bg-slate-100 dark:hover:bg-slate-800"
        aria-expanded={open === i} onclick={() => toggle(i)}><span class={scoreClass(t.word.score)}>{t.text}</span><span class="sr-only"> ({round(t.word.score)} von 100)</span></button>{:else}<span>{t.text}</span>{/if}{/each}
  </p>

  {#if selected}
    <section class="rounded-lg bg-slate-50 p-3 dark:bg-slate-800" aria-label="Laute in „{selected.word}“">
      {#if selected.phonemes.length > 0}
        <ul class="flex flex-wrap gap-3">
          {#each selected.phonemes as p, j (j)}
            <li class={p.score < 60 ? 'font-semibold text-red-700 dark:text-red-400' : ''}><span>{p.phoneme}</span> <span>{round(p.score)}</span></li>
          {/each}
        </ul>
      {/if}
      <p class="mt-2 flex items-center gap-2 text-sm">
        Tipp: hör dir das Wort langsam an
        <button type="button" class="text-xl" aria-label="„{selected.word}“ langsam anhören" onclick={() => onSpeakWord(selected.word)}>🐢</button>
      </p>
    </section>
  {/if}

  <button type="button" class="btn-primary" onclick={onAgain}>Nochmal</button>
</section>
