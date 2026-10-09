<script lang="ts">
	import '../app.css';
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { dev } from '$app/env';
	import { auth } from '#lib/auth.svelte';
	import { pwa } from '#lib/pwa.svelte';

	let pwaStarted = false;

	let { children } = $props();
	const isLogin = $derived(page.url.pathname === '/login');

	$effect(() => {
		if (!auth.token && !isLogin) void goto('/login');
	});

	$effect(() => {
		if (dev || pwaStarted) return;
		pwaStarted = true;
		void pwa.register().catch(() => undefined);
	});

	const nav = [
		{ href: '/', label: 'Übersicht' },
		{ href: '/gespraech', label: 'Gespräch' },
		{ href: '/wortschatz', label: 'Wortschatz' },
		{ href: '/aussprache', label: 'Aussprache' },
		{ href: '/fehler', label: 'Fehler' },
		{ href: '/konto', label: 'Konto' }
	];

	function logout() {
		auth.clear();
		void goto('/login');
	}
</script>

{#if pwa.offline}
	<p role="status" class="bg-amber-100 px-4 py-2 text-center text-sm text-amber-900">
		Keine Verbindung – zum Sprechen brauchst du Internet.
	</p>
{/if}
{#if pwa.updateReady}
	<p role="status" class="flex items-center justify-center gap-3 bg-brand-700 px-4 py-2 text-sm text-white">
		Neue Version von Redefluss verfügbar.
		<button
			type="button"
			class="rounded bg-white px-3 py-1 font-semibold text-brand-700"
			onclick={() => pwa.applyUpdate()}>Neu laden</button
		>
	</p>
{/if}
{#if isLogin}
	{@render children()}
{:else if auth.token}
	<header class="border-b border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
		<nav class="mx-auto flex max-w-3xl flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3 text-sm">
			<a href="/" class="flex items-center gap-2 text-base font-bold"
				><img src="/favicon.svg" alt="" class="h-7 w-7" />Redefluss</a
			>
			{#each nav as item (item.href)}
				<a
					href={item.href}
					class="text-slate-600 hover:text-brand-700 dark:text-slate-300"
					class:font-semibold={page.url.pathname === item.href}
					aria-current={page.url.pathname === item.href ? 'page' : undefined}>{item.label}</a
				>
			{/each}
			<button type="button" class="ml-auto text-slate-500 hover:text-brand-700" onclick={logout}
				>Abmelden</button
			>
		</nav>
	</header>
	<main class="mx-auto max-w-3xl px-4 py-6">{@render children()}</main>
{/if}
