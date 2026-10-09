import { render, screen } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import Page from './+page.svelte';
import { deps } from '#lib/api';

describe('Übersicht', () => {
	it('shows streak, minutes, turns left and top mistakes', async () => {
		deps.fetch = vi.fn(
			async () =>
				new Response(
					JSON.stringify({
						streakDays: 4,
						minutesToday: 12,
						turnsToday: 20,
						turnsLeft: 280,
						vocabDue: 5,
						azureSecondsLeft: 3 * 3600 + 12 * 60,
						pronunciationEnabled: true,
						topMistakes: [
							{
								id: 1,
								category: 'artikel',
								wrong: 'den ganzen Zeit',
								right: 'die ganze Zeit',
								rule: 'r',
								example: 'Ich habe den ganzen Zeit gewartet.',
								count: 3,
								lastSeen: '2026-10-08T10:00:00Z',
								resolved: false
							}
						]
					}),
					{ status: 200, headers: { 'Content-Type': 'application/json' } }
				)
		) as any;
		render(Page);
		expect(await screen.findByText('4 Tage')).toBeInTheDocument();
		expect(screen.getByText('12 min')).toBeInTheDocument();
		expect(screen.getByText('280')).toBeInTheDocument();
		expect(screen.getByText('die ganze Zeit')).toBeInTheDocument();
		expect(screen.getByText('3×')).toBeInTheDocument();
		expect(screen.getByRole('link', { name: 'Jetzt sprechen' })).toHaveAttribute(
			'href',
			'/gespraech'
		);
		expect(screen.getByRole('link', { name: /Fällige Karten: 5/ })).toHaveAttribute(
			'href',
			'/wortschatz'
		);
		expect(
			screen.getByRole('link', { name: /Aussprache: noch 3 h 12 min diesen Monat/ })
		).toHaveAttribute('href', '/aussprache');
	});

	it('first day: no streak, no mistakes yet', async () => {
		deps.fetch = vi.fn(
			async () =>
				new Response(
					JSON.stringify({
						streakDays: 0,
						minutesToday: 0,
						turnsToday: 0,
						turnsLeft: 300,
						vocabDue: 0,
						azureSecondsLeft: 16200,
						pronunciationEnabled: false,
						topMistakes: []
					}),
					{ status: 200, headers: { 'Content-Type': 'application/json' } }
				)
		) as any;
		render(Page);
		expect(await screen.findByText(/Noch keine Fehler gesammelt/)).toBeInTheDocument();
		expect(screen.getByText('0 Tage')).toBeInTheDocument();
		expect(screen.getByText('Aussprache: noch nicht eingerichtet')).toBeInTheDocument();
		expect(screen.queryByText(/diesen Monat/)).toBeNull();
	});
});
