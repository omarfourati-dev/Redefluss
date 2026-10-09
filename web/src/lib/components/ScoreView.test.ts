import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import ScoreView from './ScoreView.svelte';
import type { Assessment } from '#lib/types';

const assessment: Assessment = {
  recognized: 'Die Brücke is', accuracy: 71.4, fluency: 88, completeness: 75, pronunciation: 78.6,
  words: [
    { word: 'Die', score: 95, errorType: 'None', phonemes: [{ phoneme: 'd', score: 96 }, { phoneme: 'iː', score: 94 }] },
    { word: 'Brücke', score: 70, errorType: 'None', phonemes: [{ phoneme: 'b', score: 90 }, { phoneme: 'ʁ', score: 80 }, { phoneme: 'ʏ', score: 45 }] },
    { word: 'ist', score: 40, errorType: 'Mispronunciation', phonemes: [{ phoneme: 'ɪ', score: 30 }, { phoneme: 's', score: 90 }, { phoneme: 't', score: 60 }] },
    { word: 'lang', score: 0, errorType: 'Omission', phonemes: [] }
  ]
};

function setup() {
  const onSpeakWord = vi.fn();
  const onAgain = vi.fn();
  render(ScoreView, { text: 'Die Brücke ist lang.', assessment, onSpeakWord, onAgain });
  return { onSpeakWord, onAgain };
}

describe('ScoreView', () => {
  it('shows the four scores as numbers 0–100', () => {
    setup();
    const score = (label: string) => within(screen.getByText(label).closest('div')!).getByText(/^\d+$/).textContent;
    expect(score('Gesamt')).toBe('79');
    expect(score('Genauigkeit')).toBe('71');
    expect(score('Flüssigkeit')).toBe('88');
    expect(score('Vollständigkeit')).toBe('75');
  });

  it('colours the reference words by score and strikes omitted words through', () => {
    setup();
    expect(screen.getByText('Die')).toHaveClass('text-green-700');
    expect(screen.getByText('Brücke')).toHaveClass('text-amber-600');
    expect(screen.getByText('ist')).toHaveClass('text-red-700');
    const omitted = screen.getByText('lang.');
    expect(omitted).toHaveClass('line-through');
    expect(omitted).toHaveClass('text-slate-400');
    expect(screen.queryByRole('button', { name: /lang/ })).toBeNull();
  });

  it('a tapped word shows its phonemes with scores, weak ones red, and speaks the word slowly', async () => {
    const { onSpeakWord } = setup();
    expect(screen.queryByText(/Tipp: hör dir das Wort langsam an/)).toBeNull();
    const btn = screen.getByRole('button', { name: /^ist/ });
    expect(btn).toHaveAttribute('aria-expanded', 'false');
    await fireEvent.click(btn);
    expect(btn).toHaveAttribute('aria-expanded', 'true');
    const panel = screen.getByRole('region', { name: 'Laute in „ist“' });
    expect(within(panel).getByText('ɪ').closest('li')).toHaveClass('text-red-700');
    expect(within(panel).getByText('30')).toBeInTheDocument();
    expect(within(panel).getByText('s').closest('li')).not.toHaveClass('text-red-700');
    expect(within(panel).getByText('t').closest('li')).not.toHaveClass('text-red-700');
    expect(within(panel).getByText(/Tipp: hör dir das Wort langsam an/)).toBeInTheDocument();
    await fireEvent.click(within(panel).getByRole('button', { name: '„ist“ langsam anhören' }));
    expect(onSpeakWord).toHaveBeenCalledWith('ist');
    // tapping another word switches the panel
    await fireEvent.click(screen.getByRole('button', { name: /^Brücke/ }));
    expect(screen.queryByRole('region', { name: 'Laute in „ist“' })).toBeNull();
    expect(screen.getByRole('region', { name: 'Laute in „Brücke“' })).toBeInTheDocument();
  });

  it('words are keyboard-accessible buttons', () => {
    setup();
    for (const w of ['Die', 'Brücke', 'ist']) expect(screen.getByRole('button', { name: new RegExp(`^${w}`) }).tagName).toBe('BUTTON');
  });

  it('"Nochmal" resets for a new recording', async () => {
    const { onAgain } = setup();
    await fireEvent.click(screen.getByRole('button', { name: 'Nochmal' }));
    expect(onAgain).toHaveBeenCalled();
  });

  it('matches words case- and punctuation-insensitively and leaves unmatched ones uncoloured', () => {
    render(ScoreView, { text: '„die“ – Brücke, 9 ist', assessment, onSpeakWord: vi.fn(), onAgain: vi.fn() });
    expect(screen.getByText('„die“')).toHaveClass('text-green-700');
    expect(screen.getByText('Brücke,')).toHaveClass('text-amber-600');
    expect(screen.getByText('–').className).not.toMatch(/text-(green|amber|red)/);
    expect(screen.getByText('9').className).not.toMatch(/text-(green|amber|red)/);
    expect(screen.getByText('ist')).toHaveClass('text-red-700');
  });
});
