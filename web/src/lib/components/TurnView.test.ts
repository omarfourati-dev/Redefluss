import { render, screen } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import TurnView from './TurnView.svelte';

const turn = {
  transcript: 'Ich habe den ganzen Zeit gearbeitet.',
  corrections: [{ wrong: 'den ganzen Zeit', right: 'die ganze Zeit', rule: '„Zeit“ ist feminin.', category: 'artikel' }],
  natural: 'Ich habe die ganze Zeit gearbeitet.', reply: 'Woran hast du gearbeitet?', replyAudio: null, replyAudioType: null, turnsLeft: 299,
};

describe('TurnView', () => {
  it('shows marked transcript, correction, natural version and reply', () => {
    render(TurnView, { turn });
    expect(screen.getByText('den ganzen Zeit', { selector: 'mark' })).toBeInTheDocument();
    expect(screen.getByText('die ganze Zeit')).toBeInTheDocument();
    expect(screen.getByText('„Zeit“ ist feminin.')).toBeInTheDocument();
    expect(screen.getByText('Artikel')).toBeInTheDocument();
    expect(screen.getByText('Ich habe die ganze Zeit gearbeitet.')).toBeInTheDocument();
    expect(screen.getByText('Woran hast du gearbeitet?')).toBeInTheDocument();
  });

  it('praises an error-free sentence', () => {
    render(TurnView, { turn: { ...turn, corrections: [], natural: turn.transcript } });
    expect(screen.getByText(/Fehlerfrei/)).toBeInTheDocument();
  });
});
