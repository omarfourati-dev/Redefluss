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
	it('ignores an older response that arrives after a newer filter was chosen', async () => {
		const pending: Record<string, (r: Response) => void> = {};
		deps.fetch = vi.fn(
			(url: string) => new Promise<Response>((resolve) => (pending[url] = resolve))
		) as any;
		render(Page);
		await fireEvent.click(screen.getByRole('button', { name: 'Gelernt' }));
		await vi.waitFor(() => expect(pending['/api/mistakes?status=resolved']).toBeDefined());
		const json = (body: unknown) =>
			new Response(JSON.stringify(body), {
				status: 200,
				headers: { 'Content-Type': 'application/json' }
			});
		pending['/api/mistakes?status=resolved'](json([{ ...m, id: 8, right: 'gelernt-eintrag', resolved: true }]));
		expect(await screen.findByText('gelernt-eintrag')).toBeInTheDocument();
		pending['/api/mistakes?status=open'](json([m]));
		await new Promise((r) => setTimeout(r, 20));
		expect(screen.queryByText('du musst')).not.toBeInTheDocument();
		expect(screen.getByText('gelernt-eintrag')).toBeInTheDocument();
	});

	it('clears the error after a successful refetch and hides the empty text while failed', async () => {
		let first = true;
		deps.fetch = vi.fn(async () => {
			if (first) {
				first = false;
				return new Response(JSON.stringify({ detail: 'Kaputt.' }), {
					status: 500,
					headers: { 'Content-Type': 'application/problem+json' }
				});
			}
			return new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } });
		}) as any;
		render(Page);
		expect(await screen.findByRole('alert')).toBeInTheDocument();
		expect(screen.queryByText(/Keine offenen Fehler/)).not.toBeInTheDocument();
		await fireEvent.click(screen.getByRole('button', { name: 'Gelernt' }));
		await vi.waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
		expect(screen.getByText('Noch nichts als gelernt markiert.')).toBeInTheDocument();
	});
});
