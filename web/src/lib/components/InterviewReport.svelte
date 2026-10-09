<script lang="ts">
  import type { InterviewReport } from '#lib/types';
  import Corrections from './Corrections.svelte';

  let { report }: { report: InterviewReport } = $props();
</script>

<div class="flex flex-col gap-4">
  <section class="card" aria-label="Gesamteindruck">
    <p class="text-2xl font-bold leading-snug">{report.overall}</p>
    <p class="mt-2 text-slate-700 dark:text-slate-300">{report.summary}</p>
  </section>

  {#if report.strengths.length > 0}
    <section class="card" aria-labelledby="ir-good">
      <h2 id="ir-good" class="mb-2 text-lg font-semibold">Das lief gut</h2>
      <ul class="list-disc pl-5">
        {#each report.strengths as s, i (i)}<li>{s}</li>{/each}
      </ul>
    </section>
  {/if}

  {#if report.improvements.length > 0}
    <section class="card" aria-labelledby="ir-work">
      <h2 id="ir-work" class="mb-2 text-lg font-semibold">Daran kannst du arbeiten</h2>
      <ul class="list-disc pl-5">
        {#each report.improvements as s, i (i)}<li>{s}</li>{/each}
      </ul>
    </section>
  {/if}

  {#if report.answers.length > 0}
    <section aria-labelledby="ir-answers">
      <h2 id="ir-answers" class="mb-2 text-lg font-semibold">Deine Antworten</h2>
      <ol class="flex flex-col gap-3">
        {#each report.answers as a, i (i)}
          <li class="card flex flex-col gap-2">
            <p><span class="block text-xs font-semibold uppercase text-slate-500">Frage</span>{a.question}</p>
            <p><span class="block text-xs font-semibold uppercase text-slate-500">Deine Antwort</span>{a.answer}</p>
            <p><span class="block text-xs font-semibold uppercase text-slate-500">Feedback</span>{a.feedback}</p>
            {#if a.better}
              <p class="rounded-lg bg-emerald-50 p-2 text-emerald-900 dark:bg-emerald-950 dark:text-emerald-100">
                <span class="font-semibold">💬 Besser:</span> <span>{a.better}</span>
              </p>
            {/if}
          </li>
        {/each}
      </ol>
    </section>
  {/if}

  <section class="card" aria-labelledby="ir-mistakes">
    <h2 id="ir-mistakes" class="text-lg font-semibold">Sprachfehler</h2>
    {#if report.corrections.length > 0}
      <Corrections corrections={report.corrections} />
    {:else}
      <p class="mt-2 text-slate-600 dark:text-slate-400">Keine Sprachfehler gefunden – stark! 🎉</p>
    {/if}
  </section>
</div>
