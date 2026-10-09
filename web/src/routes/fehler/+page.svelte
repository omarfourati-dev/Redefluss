<script lang="ts">
	import { api } from '#lib/api';
	import type { Mistake } from '#lib/types';
	import { CATEGORY_LABEL } from '#lib/categories';

	const FILTERS = [
		{ key: 'open', label: 'Offen' },
		{ key: 'resolved', label: 'Gelernt' },
		{ key: 'all', label: 'Alle' }
	] as const;
	let status = $state<'open' | 'resolved' | 'all'>('open');
	let items = $state<Mistake[]>([]);
	let error = $state('');

	$effect(() => {
		const s = status;
		api<Mistake[]>(`/api/mistakes?status=${s}`)
			.then((r) => (items = r))
			.catch((e) => (error = e.message));
	});

	async function toggle(m: Mistake) {
		try {
			await api(`/api/mistakes/${m.id}`, { method: 'PATCH', json: { resolved: !m.resolved } });
			items =
				status === 'all'
					? items.map((x) => (x.id === m.id ? { ...x, resolved: !x.resolved } : x))
					: items.filter((x) => x.id !== m.id);
		} catch (e) {
			error = (e as Error).message;
		}
	}
</script>

<h1 class="mb-4 text-2xl font-bold">Deine Fehler</h1>
<div class="mb-4 flex gap-2" role="group" aria-label="Filter">
	{#each FILTERS as f (f.key)}
		<button
			type="button"
			class={status === f.key ? 'btn-primary' : 'btn-secondary'}
			aria-pressed={status === f.key}
			onclick={() => (status = f.key)}>{f.label}</button
		>
	{/each}
</div>
{#if error}<p role="alert" class="text-red-700">{error}</p>{/if}
{#if items.length === 0}
	<p class="text-slate-500">
		{status === 'resolved' ? 'Noch nichts als gelernt markiert.' : 'Keine offenen Fehler – weiter so!'}
	</p>
{/if}
<ul class="flex flex-col gap-2">
	{#each items as m (m.id)}
		<li class="card flex items-start justify-between gap-3">
			<div>
				<p>
					<span class="line-through decoration-red-500">{m.wrong}</span> →
					<strong>{m.right}</strong>
					<span class="text-sm text-slate-500">{m.count}×</span>
				</p>
				<p class="text-sm">{m.rule}</p>
				<p class="text-sm text-slate-500">
					{CATEGORY_LABEL[m.category] ?? m.category} · „{m.example}“
				</p>
			</div>
			<button type="button" class="btn-secondary shrink-0 text-sm" onclick={() => toggle(m)}
				>{m.resolved ? 'Wieder üben' : 'Sitzt jetzt'}</button
			>
		</li>
	{/each}
</ul>
