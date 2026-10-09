import { fireEvent, render, screen } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import Page from './+page.svelte';
import { deps } from '#lib/api';

const m = {
	id: 7,
	category: 'konjugation',
	wrong: 'du muss',
	right: 'du musst',
	rule: 'du → -st',
	example: 'Du muss gehen.',
	count: 2,
	lastSeen: '2026-10-08T10:00:00Z',
	resolved: false
};

describe('Fehler', () => {
	it('lists open mistakes and marks one as learned', async () => {
		deps.fetch = vi.fn(async (url: string, init?: RequestInit) => {
			if (init?.method === 'PATCH') return new Response(null, { status: 204 });
			return new Response(JSON.stringify(url.includes('status=open') ? [m] : []), {
				status: 200,
				headers: { 'Content-Type': 'application/json' }
			});
		}) as any;
		render(Page);
		expect(await screen.findByText('du musst')).toBeInTheDocument();
		await fireEvent.click(screen.getByRole('button', { name: 'Sitzt jetzt' }));
		const patch = (deps.fetch as any).mock.calls.find((c: any[]) => c[1]?.method === 'PATCH');
		expect(patch[0]).toBe('/api/mistakes/7');
		expect(patch[1].body).toBe('{"resolved":true}');
		await vi.waitFor(() => expect(screen.queryByText('du musst')).not.toBeInTheDocument());
	});

	it('switches the filter', async () => {
		deps.fetch = vi.fn(
			async () => new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } })
		) as any;
		render(Page);
		await fireEvent.click(await screen.findByRole('button', { name: 'Gelernt' }));
		await vi.waitFor(() =>
			expect((deps.fetch as any).mock.calls.at(-1)[0]).toBe('/api/mistakes?status=resolved')
		);
	});
});
