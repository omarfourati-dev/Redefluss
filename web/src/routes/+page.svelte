<script lang="ts">
	import { api } from '#lib/api';
	import type { Overview } from '#lib/types';
	import { CATEGORY_LABEL } from '#lib/categories';
	import { hoursMinutes } from '#lib/quota';

	let data = $state<Overview | null>(null);
	let error = $state('');
	$effect(() => {
		api<Overview>('/api/overview')
			.then((o) => (data = o))
			.catch((e) => (error = e.message));
	});
</script>

<h1 class="mb-4 text-2xl font-bold">Übersicht</h1>
{#if error}<p role="alert" class="text-red-700">{error}</p>{/if}
{#if data}
	<div class="grid grid-cols-3 gap-3">
		<div class="card text-center">
			<p class="text-sm text-slate-500">Serie 🔥</p>
			<p class="text-2xl font-bold">{data.streakDays} Tage</p>
		</div>
		<div class="card text-center">
			<p class="text-sm text-slate-500">Heute</p>
			<p class="text-2xl font-bold">{data.minutesToday} min</p>
		</div>
		<div class="card text-center">
			<p class="text-sm text-slate-500">Runden übrig</p>
			<p class="text-2xl font-bold">{data.turnsLeft}</p>
		</div>
	</div>
	<a href="/gespraech" class="btn-primary mt-6 w-full py-3 text-lg">Jetzt sprechen</a>
	<a href="/wortschatz" class="card mt-3 flex items-center justify-between hover:border-brand-700">
		<span class="font-semibold">Fällige Karten: {data.vocabDue}</span>
		<span class="text-sm text-brand-700">Wortschatz →</span>
	</a>
	<a href="/aussprache" class="card mt-3 flex items-center justify-between hover:border-brand-700">
		<span class="font-semibold"
			>{data.pronunciationEnabled
				? `Aussprache: noch ${hoursMinutes(data.azureSecondsLeft)} diesen Monat`
				: 'Aussprache: noch nicht eingerichtet'}</span
		>
		<span class="text-sm text-brand-700">Aussprache →</span>
	</a>
	<a href="/interview" class="card mt-3 flex items-center justify-between hover:border-brand-700">
		<span class="font-semibold">Live-Minuten heute: {data.liveMinutesLeft}</span>
		<span class="text-sm text-brand-700">Interview →</span>
	</a>
	<section class="mt-8">
		<h2 class="mb-3 text-lg font-semibold">Deine häufigsten Fehler</h2>
		{#if data.topMistakes.length === 0}
			<p class="text-slate-500">
				Noch keine Fehler gesammelt – sprich ein paar Sätze, dann siehst du hier, woran du arbeiten
				kannst.
			</p>
		{:else}
			<ul class="flex flex-col gap-2">
				{#each data.topMistakes as m (m.id)}
					<li class="card flex items-start justify-between gap-3">
						<div>
							<p>
								<span class="line-through decoration-red-500">{m.wrong}</span> →
								<strong>{m.right}</strong>
							</p>
							<p class="text-sm text-slate-500">
								{CATEGORY_LABEL[m.category] ?? m.category} · „{m.example}“
							</p>
						</div>
						<span class="rounded-full bg-amber-100 px-2 py-0.5 text-sm font-semibold text-amber-900"
							>{m.count}×</span
						>
					</li>
				{/each}
			</ul>
			<a href="/fehler" class="mt-3 inline-block text-sm underline">Alle Fehler ansehen</a>
		{/if}
	</section>
{/if}
