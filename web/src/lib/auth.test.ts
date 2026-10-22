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

	it('persists the role and ignores unknown values', async () => {
		const { auth } = await import('./auth.svelte');
		auth.set('tok', 'gast@example.de', 'guest');
		expect(localStorage.getItem('redefluss.role')).toBe('guest');
		vi.resetModules();
		expect((await import('./auth.svelte')).auth.role).toBe('guest');
		auth.set('tok2', 'gast@example.de'); // a token refresh without role keeps the known one
		expect(auth.role).toBe('guest');
		auth.setRole('admin');
		expect(auth.role).toBeNull();
		auth.setRole('owner');
		auth.clear();
		expect(auth.role).toBeNull();
		expect(localStorage.getItem('redefluss.role')).toBeNull();
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
