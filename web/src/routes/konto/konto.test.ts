import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Page from './+page.svelte';
import { deps } from '#lib/api';
import { auth } from '#lib/auth.svelte';

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

async function fill(a: string, b: string, c: string) {
	await fireEvent.input(screen.getByLabelText('Aktuelles Passwort'), { target: { value: a } });
	await fireEvent.input(screen.getByLabelText('Neues Passwort'), { target: { value: b } });
	await fireEvent.input(screen.getByLabelText('Neues Passwort wiederholen'), {
		target: { value: c }
	});
	await fireEvent.click(screen.getByRole('button', { name: 'Passwort ändern' }));
}

/** Answers by "METHOD path"; anything else fails the test. */
function mockApi(handlers: Record<string, (init?: RequestInit) => Response>) {
	deps.fetch = vi.fn(async (url: string, init?: RequestInit) => {
		const key = `${init?.method ?? 'GET'} ${url}`;
		const handler = handlers[key];
		if (!handler) throw new Error(`unexpected ${key}`);
		return handler(init);
	}) as any;
}

const invitation = { id: 7, email: 'gast@example.de', createdAt: '2026-10-22T10:00:00Z', expiresAt: '2026-10-29T10:00:00Z', status: 'open' };
const owner = { id: 1, email: 'omar@example.de', role: 'owner', createdAt: '2026-10-01T10:00:00Z' };
const guest = { id: 2, email: 'freundin@example.de', role: 'guest', createdAt: '2026-10-20T10:00:00Z' };

describe('Konto', () => {
	beforeEach(() => auth.set('tok', 'omar@example.de', 'guest'));

	it('checks the repetition locally', async () => {
		deps.fetch = vi.fn() as any;
		render(Page);
		await fill('altes-passwort-1', 'neues-passwort-12', 'anderes-passwort');
		expect(await screen.findByRole('alert')).toHaveTextContent(
			'Die neuen Passwörter stimmen nicht überein.'
		);
		expect(deps.fetch).not.toHaveBeenCalled();
	});

	it('changes the password and keeps the user logged in with the new token', async () => {
		deps.fetch = vi.fn(async () => json(200, { token: 'neu', email: 'omar@example.de', role: 'guest' })) as any;
		render(Page);
		await fill('altes-passwort-1', 'neues-passwort-12', 'neues-passwort-12');
		expect(await screen.findByText('Passwort geändert.')).toBeInTheDocument();
		expect(auth.token).toBe('neu');
	});

	it('hides guest management from guests', async () => {
		deps.fetch = vi.fn() as any;
		render(Page);
		expect(screen.queryByRole('heading', { name: 'Gäste einladen' })).not.toBeInTheDocument();
		expect(deps.fetch).not.toHaveBeenCalled();
	});

	it('learns the role of an older session from the server', async () => {
		auth.setRole(null);
		mockApi({
			'GET /api/auth/me': () => json(200, { email: 'omar@example.de', role: 'owner' }),
			'GET /api/invitations': () => json(200, []),
			'GET /api/users': () => json(200, [owner])
		});
		render(Page);
		expect(await screen.findByRole('heading', { name: 'Gäste einladen' })).toBeInTheDocument();
		expect(auth.role).toBe('owner');
		expect(await screen.findByText('Noch keine Gäste.')).toBeInTheDocument();
	});

	describe('as owner', () => {
		beforeEach(() => auth.setRole('owner'));

		it('creates an invitation link to send by hand', async () => {
			let invitations: unknown[] = [];
			mockApi({
				'GET /api/invitations': () => json(200, invitations),
				'GET /api/users': () => json(200, [owner]),
				'POST /api/invitations': (init) => {
					expect(JSON.parse(init!.body as string)).toEqual({ email: 'gast@example.de' });
					invitations = [invitation];
					return json(201, { ...invitation, token: 'geheim-123' });
				}
			});
			render(Page);
			await fireEvent.input(await screen.findByLabelText('E-Mail des Gastes'), { target: { value: 'gast@example.de' } });
			await fireEvent.click(screen.getByRole('button', { name: 'Einladung erstellen' }));
			expect(await screen.findByDisplayValue(`${location.origin}/einladung#geheim-123`)).toBeInTheDocument();
			expect(await screen.findByText(/offen bis 29\.10\.2026/)).toBeInTheDocument();
		});

		it('shows the server message when the address is taken', async () => {
			mockApi({
				'GET /api/invitations': () => json(200, []),
				'GET /api/users': () => json(200, [owner]),
				'POST /api/invitations': () =>
					new Response(JSON.stringify({ status: 409, detail: 'Für diese E-Mail gibt es schon ein Konto.' }), {
						status: 409,
						headers: { 'Content-Type': 'application/problem+json' }
					})
			});
			render(Page);
			await fireEvent.input(await screen.findByLabelText('E-Mail des Gastes'), { target: { value: 'omar@example.de' } });
			await fireEvent.click(screen.getByRole('button', { name: 'Einladung erstellen' }));
			expect(await screen.findByRole('alert')).toHaveTextContent('Für diese E-Mail gibt es schon ein Konto.');
		});

		it('withdraws an open invitation', async () => {
			let invitations: unknown[] = [invitation];
			mockApi({
				'GET /api/invitations': () => json(200, invitations),
				'GET /api/users': () => json(200, [owner]),
				'DELETE /api/invitations/7': () => {
					invitations = [];
					return new Response(null, { status: 204 });
				}
			});
			render(Page);
			await fireEvent.click(await screen.findByRole('button', { name: 'Zurückziehen' }));
			await vi.waitFor(() => expect(screen.queryByText('gast@example.de')).not.toBeInTheDocument());
		});

		it('removes a guest only after confirmation', async () => {
			let accounts = [owner, guest];
			mockApi({
				'GET /api/invitations': () => json(200, []),
				'GET /api/users': () => json(200, accounts),
				'DELETE /api/users/2': () => {
					accounts = [owner];
					return new Response(null, { status: 204 });
				}
			});
			const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true);
			render(Page);
			const remove = await screen.findByRole('button', { name: 'Entfernen' });
			await fireEvent.click(remove);
			expect(deps.fetch).not.toHaveBeenCalledWith('/api/users/2', expect.anything());
			await fireEvent.click(remove);
			expect(await screen.findByText('Noch keine Gäste.')).toBeInTheDocument();
			expect(confirm).toHaveBeenCalledTimes(2);
			confirm.mockRestore();
		});
	});
});
