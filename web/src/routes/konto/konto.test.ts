import { fireEvent, render, screen } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import Page from './+page.svelte';
import { deps } from '#lib/api';
import { auth } from '#lib/auth.svelte';

async function fill(a: string, b: string, c: string) {
	await fireEvent.input(screen.getByLabelText('Aktuelles Passwort'), { target: { value: a } });
	await fireEvent.input(screen.getByLabelText('Neues Passwort'), { target: { value: b } });
	await fireEvent.input(screen.getByLabelText('Neues Passwort wiederholen'), {
		target: { value: c }
	});
	await fireEvent.click(screen.getByRole('button', { name: 'Passwort ändern' }));
}

describe('Konto', () => {
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
		deps.fetch = vi.fn(
			async () =>
				new Response(JSON.stringify({ token: 'neu', email: 'omar@example.de' }), {
					status: 200,
					headers: { 'Content-Type': 'application/json' }
				})
		) as any;
		render(Page);
		await fill('altes-passwort-1', 'neues-passwort-12', 'neues-passwort-12');
		expect(await screen.findByText('Passwort geändert.')).toBeInTheDocument();
		expect(auth.token).toBe('neu');
	});
});
