import { render, screen, fireEvent } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import Page from './+page.svelte';
import { deps } from '#lib/api';
import { auth } from '#lib/auth.svelte';

describe('login page', () => {
	it('logs in and stores the token', async () => {
		deps.goto = vi.fn();
		deps.fetch = vi.fn(
			async () =>
				new Response(JSON.stringify({ token: 't', email: 'omar@example.de' }), {
					status: 200,
					headers: { 'Content-Type': 'application/json' }
				})
		);
		render(Page);
		await fireEvent.input(screen.getByLabelText('E-Mail'), {
			target: { value: 'omar@example.de' }
		});
		await fireEvent.input(screen.getByLabelText('Passwort'), {
			target: { value: 'mein-sicheres-passwort' }
		});
		await fireEvent.click(screen.getByRole('button', { name: 'Anmelden' }));
		await vi.waitFor(() => expect(auth.token).toBe('t'));
		expect(deps.goto).toHaveBeenCalledWith('/');
	});

	it('shows the server message on failure', async () => {
		deps.fetch = vi.fn(
			async () =>
				new Response(
					JSON.stringify({ status: 401, detail: 'E-Mail oder Passwort ist falsch.' }),
					{ status: 401, headers: { 'Content-Type': 'application/problem+json' } }
				)
		);
		render(Page);
		await fireEvent.input(screen.getByLabelText('E-Mail'), { target: { value: 'x@y.de' } });
		await fireEvent.input(screen.getByLabelText('Passwort'), { target: { value: 'falsch' } });
		await fireEvent.click(screen.getByRole('button', { name: 'Anmelden' }));
		expect(await screen.findByRole('alert')).toHaveTextContent('E-Mail oder Passwort ist falsch.');
	});
});
