<script lang="ts">
	import { onMount } from 'svelte';
	import { api, deps } from '#lib/api';
	import { auth } from '#lib/auth.svelte';

	// The token travels in the URL fragment (#…): browsers never send it to the server or into its logs.
	let token = '';
	let email = $state<string | null>(null);
	let loading = $state(true);
	let failed = $state('');
	let password = $state('');
	let repeat = $state('');
	let error = $state('');
	let busy = $state(false);

	onMount(async () => {
		token = location.hash.slice(1);
		if (!token) {
			failed = 'Dieser Link ist unvollständig. Bitte öffne den ganzen Link aus deiner Einladung.';
			loading = false;
			return;
		}
		try {
			email = (await api<{ email: string }>('/api/invitations/preview', { method: 'POST', json: { token } })).email;
		} catch (err) {
			failed = (err as Error).message;
		} finally {
			loading = false;
		}
	});

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		error = '';
		if (password !== repeat) {
			error = 'Die Passwörter stimmen nicht überein.';
			return;
		}
		busy = true;
		try {
			const res = await api<{ token: string; email: string; role: string }>('/api/invitations/accept', {
				method: 'POST',
				json: { token, password }
			});
			auth.set(res.token, res.email, res.role);
			history.replaceState(history.state, '', '/einladung'); // the used link leaves the address bar
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
		<h1 class="mt-3 text-2xl font-bold">Willkommen bei Redefluss</h1>
		<p class="text-slate-500">Dein Deutsch-Sprechtrainer</p>
	</div>
	{#if loading}
		<p class="text-center text-slate-500" role="status">Einladung wird geprüft …</p>
	{:else if failed}
		<div class="card flex flex-col gap-3">
			<p role="alert" class="text-sm text-red-700 dark:text-red-400">{failed}</p>
			<a class="btn-primary text-center" href="/login">Zur Anmeldung</a>
		</div>
	{:else}
		<form class="card flex flex-col gap-4" onsubmit={submit}>
			<p class="text-sm">
				Du wurdest eingeladen. Leg ein Passwort fest, dann kannst du sofort loslegen. Deine Übungen,
				Fehler und Wörter siehst nur du.
			</p>
			<label class="flex flex-col gap-1 text-sm font-medium"
				>E-Mail
				<input class="input" type="email" autocomplete="username" value={email} readonly />
			</label>
			<label class="flex flex-col gap-1 text-sm font-medium"
				>Passwort
				<input
					class="input"
					type="password"
					autocomplete="new-password"
					minlength="12"
					required
					bind:value={password}
				/>
			</label>
			<label class="flex flex-col gap-1 text-sm font-medium"
				>Passwort wiederholen
				<input
					class="input"
					type="password"
					autocomplete="new-password"
					minlength="12"
					required
					bind:value={repeat}
				/>
			</label>
			<p class="text-xs text-slate-500">Mindestens 12 Zeichen.</p>
			{#if auth.token && auth.email}
				<p class="text-xs text-amber-700 dark:text-amber-400">
					Du bist gerade als {auth.email} angemeldet. Mit dem neuen Konto wirst du dort abgemeldet.
				</p>
			{/if}
			{#if error}<p role="alert" class="text-sm text-red-700 dark:text-red-400">{error}</p>{/if}
			<button class="btn-primary" type="submit" disabled={busy}>Konto anlegen</button>
		</form>
	{/if}
</main>
