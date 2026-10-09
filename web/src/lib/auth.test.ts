import { beforeEach, describe, expect, it, vi } from 'vitest';

describe('auth', () => {
	beforeEach(() => {
		localStorage.clear();
		vi.resetModules();
	});

	it('persists token and email and clears them', async () => {
		const { auth } = await import('./auth.svelte');
		auth.set('tok', 'omar@example.de');
		expect(localStorage.getItem('redefluss.token')).toBe('tok');
		vi.resetModules();
		const again = (await import('./auth.svelte')).auth; // fresh module reads storage
		expect(again.token).toBe('tok');
		auth.clear();
		expect(auth.token).toBeNull();
		expect(localStorage.getItem('redefluss.token')).toBeNull();
	});

	it('works when storage throws (private mode)', async () => {
		vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
			throw new Error('blocked');
		});
		vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
			throw new Error('blocked');
		});
		const { auth } = await import('./auth.svelte');
		expect(auth.token).toBeNull();
		auth.set('tok', 'a@b.de');
		expect(auth.token).toBe('tok');
		vi.restoreAllMocks();
	});
});
