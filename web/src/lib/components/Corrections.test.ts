import { render, screen } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import Corrections from './Corrections.svelte';

describe('Corrections', () => {
  it('lists wrong → right with category label and rule', () => {
    render(Corrections, { corrections: [{ wrong: 'der Zeit', right: 'die Zeit', rule: '„Zeit“ ist feminin.', category: 'artikel' }] });
    expect(screen.getByText('der Zeit')).toHaveClass('line-through');
    expect(screen.getByText('die Zeit')).toBeInTheDocument();
    expect(screen.getByText('Artikel')).toBeInTheDocument();
    expect(screen.getByText('„Zeit“ ist feminin.')).toBeInTheDocument();
  });

  it('renders nothing without corrections', () => {
    const { container } = render(Corrections, { corrections: [] });
    expect(container.querySelector('ul')).toBeNull();
  });
});
