<script lang="ts">
	import '../app.css';
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { auth } from '$lib/auth.svelte';

	let { children } = $props();
	const isLogin = $derived(page.url.pathname === '/login');

	$effect(() => {
		if (!auth.token && !isLogin) void goto('/login');
	});

	const nav = [
		{ href: '/', label: 'Übersicht' },
		{ href: '/gespraech', label: 'Gespräch' },
		{ href: '/fehler', label: 'Fehler' },
		{ href: '/konto', label: 'Konto' }
	];

	function logout() {
		auth.clear();
		void goto('/login');
	}
</script>

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
