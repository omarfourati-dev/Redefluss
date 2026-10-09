import { render, screen, within } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import InterviewReport from './InterviewReport.svelte';

const report = {
  overall: 'Solides Gespräch mit klaren Beispielen.',
  summary: 'Du hast deine Projekte gut erklärt, bei der Motivation warst du noch vage.',
  strengths: ['Konkrete Beispiele aus deinen Projekten'],
  improvements: ['Motivation für die Stelle schärfen'],
  answers: [{
    question: 'Warum diese Stelle?',
    answer: 'Weil ich will mehr lernen.',
    feedback: 'Zu allgemein – nenn etwas aus der Anzeige.',
    better: 'Mich reizt vor allem die Arbeit mit Kotlin im Backend.'
  }],
  corrections: [{ wrong: 'Weil ich will', right: 'Weil ich … will', rule: 'Nach „weil“ steht das Verb am Ende.', category: 'verbstellung' }]
};

describe('InterviewReport', () => {
  it('shows verdict, summary, strengths, improvements, answers and corrections', () => {
    render(InterviewReport, { report });
    expect(screen.getByText('Solides Gespräch mit klaren Beispielen.')).toBeInTheDocument();
    expect(screen.getByText(/bei der Motivation warst du noch vage/)).toBeInTheDocument();
    const good = screen.getByRole('region', { name: 'Das lief gut' });
    expect(within(good).getByText('Konkrete Beispiele aus deinen Projekten')).toBeInTheDocument();
    const work = screen.getByRole('region', { name: 'Daran kannst du arbeiten' });
    expect(within(work).getByText('Motivation für die Stelle schärfen')).toBeInTheDocument();
    const answers = screen.getByRole('region', { name: 'Deine Antworten' });
    expect(within(answers).getByText('Warum diese Stelle?')).toBeInTheDocument();
    expect(within(answers).getByText('Weil ich will mehr lernen.')).toBeInTheDocument();
    expect(within(answers).getByText('Zu allgemein – nenn etwas aus der Anzeige.')).toBeInTheDocument();
    expect(within(answers).getByText(/💬 Besser:/)).toBeInTheDocument();
    expect(within(answers).getByText('Mich reizt vor allem die Arbeit mit Kotlin im Backend.')).toBeInTheDocument();
    const mistakes = screen.getByRole('region', { name: 'Sprachfehler' });
    expect(within(mistakes).getByText('Nach „weil“ steht das Verb am Ende.')).toBeInTheDocument();
    expect(within(mistakes).getByText('Weil ich … will')).toBeInTheDocument();
  });

  it('leaves out empty lists and praises an error-free conversation', () => {
    render(InterviewReport, { report: { ...report, strengths: [], improvements: [], answers: [], corrections: [] } });
    expect(screen.queryByRole('region', { name: 'Das lief gut' })).toBeNull();
    expect(screen.queryByRole('region', { name: 'Daran kannst du arbeiten' })).toBeNull();
    expect(screen.queryByRole('region', { name: 'Deine Antworten' })).toBeNull();
    expect(screen.getByText(/Keine Sprachfehler gefunden/)).toBeInTheDocument();
  });
});
