import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import WordCard from './WordCard.svelte';
import { deps } from '#lib/api';
import { createPlayer } from '#lib/talk.svelte';
import type { Card } from '#lib/types';

const card: Card = { id: 7, word: 'Besprechung', article: 'die', plural: 'die Besprechungen', meaning: 'ein Treffen, um etwas zu klären',
  example: 'Die Besprechung beginnt um neun.', theme: 'it', source: 'daily', dueOn: '2026-10-09', intervalDays: 0, reps: 0 };

describe('WordCard', () => {
  beforeEach(() => {
    HTMLMediaElement.prototype.play = vi.fn(async () => {});
    HTMLMediaElement.prototype.pause = vi.fn();
    URL.createObjectURL = vi.fn(() => 'blob:card');
    URL.revokeObjectURL = vi.fn();
  });

  it('shows article in its colour, word, plural, meaning and example', () => {
    render(WordCard, { card, player: createPlayer() });
    expect(screen.getByText('die')).toHaveClass('text-red-700');
    expect(screen.getByText('Besprechung')).toBeInTheDocument();
    expect(screen.getByText(/die Besprechungen/)).toBeInTheDocument();
    expect(screen.getByText('ein Treffen, um etwas zu klären')).toBeInTheDocument();
    expect(screen.getByText('Die Besprechung beginnt um neun.').tagName).toBe('EM');
  });

  it('colours der blue and das green', () => {
    render(WordCard, { card: { ...card, article: 'der', word: 'Termin' }, player: createPlayer() });
    expect(screen.getByText('der')).toHaveClass('text-blue-700');
    render(WordCard, { card: { ...card, id: 8, article: 'das', word: 'Projekt' }, player: createPlayer() });
    expect(screen.getByText('das')).toHaveClass('text-green-700');
  });

  it('the review variant hides plural, example and the audio button', () => {
    render(WordCard, { card, player: createPlayer(), review: true });
    expect(screen.getByText('Besprechung')).toBeInTheDocument();
    expect(screen.getByText('ein Treffen, um etwas zu klären')).toBeInTheDocument();
    expect(screen.queryByText('Die Besprechung beginnt um neun.')).toBeNull();
    expect(screen.queryByText(/die Besprechungen/)).toBeNull();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('🔊 loads the card audio through the api client and plays it', async () => {
    deps.fetch = vi.fn(async () => new Response('mp3', { status: 200 })) as any;
    render(WordCard, { card, player: createPlayer() });
    await fireEvent.click(screen.getByRole('button', { name: 'Besprechung anhören' }));
    expect((deps.fetch as any).mock.calls[0][0]).toBe('/api/vocab/cards/7/audio');
    await vi.waitFor(() => expect(URL.createObjectURL).toHaveBeenCalled());
    const play = HTMLMediaElement.prototype.play as any;
    expect(play).toHaveBeenCalledTimes(2); // unlock inside the click + the word
    expect(play.mock.contexts[0]).toBe(play.mock.contexts[1]);
  });

  it('shows an error when the audio cannot be loaded', async () => {
    deps.fetch = vi.fn(async () => new Response(JSON.stringify({ status: 502, detail: 'Der Sprachdienst antwortet gerade nicht. Bitte versuch es noch einmal.' }), { status: 502 })) as any;
    render(WordCard, { card, player: createPlayer() });
    await fireEvent.click(screen.getByRole('button', { name: 'Besprechung anhören' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Der Sprachdienst antwortet gerade nicht');
  });

  it('audio arriving after the player was destroyed is not played', async () => {
    let answer: (r: Response) => void = () => {};
    deps.fetch = vi.fn(() => new Promise<Response>((r) => { answer = r; })) as any;
    const player = createPlayer();
    render(WordCard, { card, player });
    await fireEvent.click(screen.getByRole('button', { name: 'Besprechung anhören' }));
    player.destroy();
    answer(new Response('mp3', { status: 200 }));
    await new Promise((r) => setTimeout(r, 20));
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });
});
