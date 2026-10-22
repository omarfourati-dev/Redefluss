<script lang="ts">
	import { onMount } from 'svelte';
	import { api } from '#lib/api';

	type Invitation = { id: number; email: string; createdAt: string; expiresAt: string; status: 'open' | 'used' | 'expired' };
	type Account = { id: number; email: string; role: 'owner' | 'guest'; createdAt: string };

	let invitations = $state<Invitation[]>([]);
	let guests = $state<Account[]>([]);
	let email = $state('');
	let link = $state('');
	let copied = $state(false);
	let error = $state('');
	let busy = $state(false);

	const date = (iso: string) => new Date(iso).toLocaleDateString('de-DE');
	const statusText = (i: Invitation) =>
		i.status === 'used' ? 'angenommen' : i.status === 'expired' ? 'abgelaufen' : `offen bis ${date(i.expiresAt)}`;

	async function load() {
		const [inv, accounts] = await Promise.all([
			api<Invitation[]>('/api/invitations'),
			api<Account[]>('/api/users')
		]);
		invitations = inv;
		guests = accounts.filter((a) => a.role === 'guest');
	}

	onMount(() => {
		load().catch((err) => (error = (err as Error).message));
	});

	async function create(e: SubmitEvent) {
		e.preventDefault();
		error = '';
		link = '';
		copied = false;
		busy = true;
		try {
			const res = await api<{ token: string }>('/api/invitations', { method: 'POST', json: { email } });
			link = `${location.origin}/einladung#${res.token}`;
			email = '';
			await load();
		} catch (err) {
			error = (err as Error).message;
		} finally {
			busy = false;
		}
	}

	async function copy() {
		try {
			await navigator.clipboard.writeText(link);
			copied = true;
		} catch {
			copied = false; // the field stays selectable for copying by hand
		}
	}

	async function revoke(i: Invitation) {
		error = '';
		try {
			await api(`/api/invitations/${i.id}`, { method: 'DELETE' });
			await load();
		} catch (err) {
			error = (err as Error).message;
		}
	}

	async function remove(a: Account) {
		if (!confirm(`${a.email} und alle Übungen, Fehler und Wörter dieses Kontos endgültig löschen?`)) return;
		error = '';
		try {
			await api(`/api/users/${a.id}`, { method: 'DELETE' });
			await load();
		} catch (err) {
			error = (err as Error).message;
		}
	}
</script>

<section class="card mt-6 flex max-w-md flex-col gap-4" aria-labelledby="einladungen">
	<h2 id="einladungen" class="text-lg font-bold">Gäste einladen</h2>
	<p class="text-sm text-slate-500">
		Gäste bekommen ein eigenes Konto mit eigenen Daten und kleineren Tageslimits. Redefluss verschickt
		keine E-Mails: Du schickst den Link selbst, z. B. per WhatsApp.
	</p>
	<form class="flex flex-col gap-2" onsubmit={create}>
		<label class="flex flex-col gap-1 text-sm font-medium"
			>E-Mail des Gastes
			<input class="input" type="email" required bind:value={email} />
		</label>
		<button class="btn-primary" type="submit" disabled={busy}>Einladung erstellen</button>
	</form>
	{#if link}
		<div class="flex flex-col gap-2 rounded-lg bg-emerald-50 p-3 text-sm dark:bg-emerald-950">
			<label class="flex flex-col gap-1 font-medium"
				>Einladungslink (7 Tage gültig, nur einmal nutzbar)
				<input class="input font-mono text-xs" readonly value={link} onfocus={(e) => e.currentTarget.select()} />
			</label>
			<button type="button" class="self-start text-brand-700 hover:underline" onclick={copy}
				>{copied ? 'Kopiert ✓' : 'Link kopieren'}</button
			>
		</div>
	{/if}
	{#if error}<p role="alert" class="text-sm text-red-700 dark:text-red-400">{error}</p>{/if}

	{#if invitations.length}
		<h3 class="text-sm font-semibold">Einladungen</h3>
		<ul class="flex flex-col gap-1 text-sm">
			{#each invitations as i (i.id)}
				<li class="flex items-center justify-between gap-2">
					<span>{i.email} <span class="text-slate-500">· {statusText(i)}</span></span>
					{#if i.status === 'open'}
						<button type="button" class="text-red-700 hover:underline" onclick={() => revoke(i)}
							>Zurückziehen</button
						>
					{/if}
				</li>
			{/each}
		</ul>
	{/if}

	<h3 class="text-sm font-semibold">Gäste</h3>
	{#if guests.length}
		<ul class="flex flex-col gap-1 text-sm">
			{#each guests as a (a.id)}
				<li class="flex items-center justify-between gap-2">
					<span>{a.email} <span class="text-slate-500">· seit {date(a.createdAt)}</span></span>
					<button type="button" class="text-red-700 hover:underline" onclick={() => remove(a)}
						>Entfernen</button
					>
				</li>
			{/each}
		</ul>
	{:else}
		<p class="text-sm text-slate-500">Noch keine Gäste.</p>
	{/if}
</section>
