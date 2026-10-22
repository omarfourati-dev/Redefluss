import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Page from './+page.svelte';
import { deps } from '#lib/api';
import { auth } from '#lib/auth.svelte';

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const gone = () =>
	new Response(JSON.stringify({ status: 410, detail: 'Diese Einladung ist ungültig, abgelaufen oder wurde schon benutzt.' }), {
		status: 410,
		headers: { 'Content-Type': 'application/problem+json' }
	});

function mockApi(handlers: Record<string, (init?: RequestInit) => Response>) {
	deps.fetch = vi.fn(async (url: string, init?: RequestInit) => {
		const handler = handlers[url];
		if (!handler) throw new Error(`unexpected ${url}`);
		return handler(init);
	}) as any;
}
const body = (call: unknown[]) => JSON.parse((call[1] as RequestInit).body as string);

async function setPassword(password: string, repeat: string) {
	await fireEvent.input(await screen.findByLabelText('Passwort'), { target: { value: password } });
	await fireEvent.input(screen.getByLabelText('Passwort wiederholen'), { target: { value: repeat } });
	await fireEvent.click(screen.getByRole('button', { name: 'Konto anlegen' }));
}

describe('Einladung', () => {
	beforeEach(() => {
		auth.clear();
		deps.goto = vi.fn();
		history.replaceState(null, '', '/einladung#einladungs-token');
	});

	it('shows the invited address, creates the account and logs the guest in', async () => {
		mockApi({
			'/api/invitations/preview': () => json(200, { email: 'gast@example.de' }),
			'/api/invitations/accept': () => json(201, { token: 'gast-token', email: 'gast@example.de', role: 'guest' })
		});
		render(Page);
		expect(await screen.findByDisplayValue('gast@example.de')).toBeInTheDocument();
		await setPassword('ein-langes-passwort', 'ein-langes-passwort');
		await vi.waitFor(() => expect(auth.token).toBe('gast-token'));
		expect(auth.role).toBe('guest');
		expect(deps.goto).toHaveBeenCalledWith('/');
		const calls = (deps.fetch as any).mock.calls;
		expect(body(calls[0])).toEqual({ token: 'einladungs-token' });
		expect(body(calls[1])).toEqual({ token: 'einladungs-token', password: 'ein-langes-passwort' });
		expect(location.hash).toBe(''); // the used link leaves the address bar
	});

	it('checks the repetition before sending anything', async () => {
		mockApi({ '/api/invitations/preview': () => json(200, { email: 'gast@example.de' }) });
		render(Page);
		await setPassword('ein-langes-passwort', 'ein-anderes-passwort');
		expect(await screen.findByRole('alert')).toHaveTextContent('Die Passwörter stimmen nicht überein.');
		expect(deps.fetch).toHaveBeenCalledTimes(1);
	});

	it('explains an invalid, expired or used link', async () => {
		mockApi({ '/api/invitations/preview': gone });
		render(Page);
		expect(await screen.findByRole('alert')).toHaveTextContent('Diese Einladung ist ungültig, abgelaufen oder wurde schon benutzt.');
		expect(screen.queryByLabelText('Passwort')).not.toBeInTheDocument();
		expect(screen.getByRole('link', { name: 'Zur Anmeldung' })).toHaveAttribute('href', '/login');
	});

	it('does not ask the server when the token is missing', async () => {
		history.replaceState(null, '', '/einladung');
		deps.fetch = vi.fn() as any;
		render(Page);
		expect(await screen.findByRole('alert')).toHaveTextContent('Dieser Link ist unvollständig.');
		expect(deps.fetch).not.toHaveBeenCalled();
	});

	it('shows the server message when accepting fails', async () => {
		mockApi({
			'/api/invitations/preview': () => json(200, { email: 'gast@example.de' }),
			'/api/invitations/accept': gone
		});
		render(Page);
		await setPassword('ein-langes-passwort', 'ein-langes-passwort');
		expect(await screen.findByRole('alert')).toHaveTextContent('Diese Einladung ist ungültig');
		expect(auth.token).toBeNull();
		expect(deps.goto).not.toHaveBeenCalled();
	});
});
