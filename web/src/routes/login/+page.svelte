<script lang="ts">
	import { api, deps } from '$lib/api';
	import { auth } from '$lib/auth.svelte';

	let email = $state('');
	let password = $state('');
	let error = $state('');
	let busy = $state(false);

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		busy = true;
		error = '';
		try {
			const res = await api<{ token: string; email: string }>('/api/auth/login', {
				method: 'POST',
				json: { email, password }
			});
			auth.set(res.token, res.email);
			void deps.goto('/');
		} catch (err) {
			error = (err as Error).message;
		} finally {
			busy = false;
		}
	}
</script>

<main class="mx-auto flex min-h-screen max-w-sm flex-col justify-center gap-6 px-4">
	<div class="text-center">
		<img src="/favicon.svg" alt="" class="mx-auto h-14 w-14" />
		<h1 class="mt-3 text-2xl font-bold">Redefluss</h1>
		<p class="text-slate-500">Dein Deutsch-Sprechtrainer</p>
	</div>
	<form class="card flex flex-col gap-4" onsubmit={submit}>
		<label class="flex flex-col gap-1 text-sm font-medium"
			>E-Mail
			<input class="input" type="email" autocomplete="username" required bind:value={email} />
		</label>
		<label class="flex flex-col gap-1 text-sm font-medium"
			>Passwort
			<input
				class="input"
				type="password"
				autocomplete="current-password"
				required
				bind:value={password}
			/>
		</label>
		{#if error}<p role="alert" class="text-sm text-red-700 dark:text-red-400">{error}</p>{/if}
		<button class="btn-primary" type="submit" disabled={busy}>Anmelden</button>
	</form>
</main>
