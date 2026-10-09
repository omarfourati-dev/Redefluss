import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Page from './+page.svelte';
import { deps } from '#lib/api';

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const turn = { transcript: 'Hallo du muss kommen.', corrections: [{ wrong: 'du muss', right: 'du musst', rule: 'du → -st', category: 'konjugation' }],
  natural: 'Hallo, du musst kommen.', reply: 'Wann soll ich kommen?', replyAudio: null, replyAudioType: null, turnsLeft: 12 };

describe('Gespräch', () => {
  beforeEach(() => { localStorage.clear(); });

  it('starts a topic and answers a typed sentence', async () => {
    deps.fetch = vi.fn(async (url: string) => url === '/api/sessions' ? json(201, { id: 'abc' }) : json(200, turn)) as any;
    render(Page);
    await fireEvent.click(screen.getByRole('button', { name: 'Arbeit' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Lieber tippen' }));
    await fireEvent.input(screen.getByLabelText('Dein Satz'), { target: { value: 'Hallo du muss kommen.' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Senden' }));
    expect(await screen.findByText('Wann soll ich kommen?')).toBeInTheDocument();
    expect(screen.getByText('Noch 12 Runden heute')).toBeInTheDocument();
    const form = (deps.fetch as any).mock.calls[1][1].body as FormData;
    expect(form.get('sessionId')).toBe('abc');
    expect(form.get('text')).toBe('Hallo du muss kommen.');
    expect(JSON.parse(form.get('history') as string)).toEqual([]);
  });

  it('allows only one turn at a time', async () => {
    let release: (r: Response) => void = () => {};
    deps.fetch = vi.fn((url: string) => url === '/api/sessions' ? Promise.resolve(json(201, { id: 'abc' })) : new Promise<Response>((r) => { release = r; })) as any;
    render(Page);
    await fireEvent.click(screen.getByRole('button', { name: 'Arbeit' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Lieber tippen' }));
    await fireEvent.input(screen.getByLabelText('Dein Satz'), { target: { value: 'Eins' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Senden' }));
    expect(screen.getByRole('button', { name: 'Senden' })).toBeDisabled();
    expect(screen.getByRole('button', { name: /Halten und sprechen/ })).toBeDisabled();
    await fireEvent.submit(screen.getByLabelText('Dein Satz').closest('form')!);
    expect((deps.fetch as any).mock.calls.filter((c: any[]) => c[0] === '/api/turns')).toHaveLength(1);
    release(json(200, turn));
    expect(await screen.findByText('Wann soll ich kommen?')).toBeInTheDocument();
  });

  it('shows server errors', async () => {
    deps.fetch = vi.fn(async (url: string) => url === '/api/sessions' ? json(201, { id: 'abc' })
      : json(429, { status: 429, detail: "Tageslimit erreicht (300 Runden). Morgen geht's weiter." })) as any;
    render(Page);
    await fireEvent.click(screen.getByRole('button', { name: 'Freies Thema' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Lieber tippen' }));
    await fireEvent.input(screen.getByLabelText('Dein Satz'), { target: { value: 'Hallo' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Senden' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Tageslimit erreicht');
  });
});
