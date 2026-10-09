import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/svelte';
import { afterEach } from 'vitest';

// Vitest globals are off, so testing-library cannot auto-register its cleanup.
afterEach(() => cleanup());
