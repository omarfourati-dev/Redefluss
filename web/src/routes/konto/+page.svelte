<script lang="ts">
	import { api } from '#lib/api';
	import { auth } from '#lib/auth.svelte';

	let current = $state('');
	let next = $state('');
	let repeat = $state('');
	let error = $state('');
	let done = $state(false);
	let busy = $state(false);

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		error = '';
		done = false;
		if (next !== repeat) {
			error = 'Die neuen Passwörter stimmen nicht überein.';
			return;
		}
		busy = true;
		try {
			const res = await api<{ token: string; email: string }>('/api/auth/password', {
				method: 'POST',
				json: { current, next }
			});
			auth.set(res.token, res.email);
			done = true;
			current = next = repeat = '';
		} catch (err) {
			error = (err as Error).message;
		} finally {
			busy = false;
		}
	}
</script>

<h1 class="mb-4 text-2xl font-bold">Konto</h1>
<p class="mb-4 text-slate-500">Angemeldet als {auth.email}</p>
<form class="card flex max-w-md flex-col gap-4" onsubmit={submit}>
	<label class="flex flex-col gap-1 text-sm font-medium"
		>Aktuelles Passwort
		<input
			class="input"
			type="password"
			autocomplete="current-password"
			required
			bind:value={current}
		/></label
	>
	<label class="flex flex-col gap-1 text-sm font-medium"
		>Neues Passwort
		<input
			class="input"
			type="password"
			autocomplete="new-password"
			minlength="12"
			required
			bind:value={next}
		/></label
	>
	<label class="flex flex-col gap-1 text-sm font-medium"
		>Neues Passwort wiederholen
		<input
			class="input"
			type="password"
			autocomplete="new-password"
			minlength="12"
			required
			bind:value={repeat}
		/></label
	>
	{#if error}<p role="alert" class="text-sm text-red-700">{error}</p>{/if}
	{#if done}<p class="text-sm text-emerald-700">Passwort geändert.</p>{/if}
	<button class="btn-primary" type="submit" disabled={busy}>Passwort ändern</button>
</form>
