import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, apiBlob, ApiError, deps } from './api';
import { auth } from './auth.svelte';

const res = (status: number, body: unknown, type = 'application/json') =>
	new Response(body === undefined ? null : JSON.stringify(body), {
		status,
		headers: { 'Content-Type': type }
	});

describe('api', () => {
	beforeEach(() => {
		auth.clear();
		deps.goto = vi.fn();
	});

	it('sends JSON with the bearer token', async () => {
		auth.set('tok', 'a@b.de');
		deps.fetch = vi.fn(async () => res(200, { ok: true }));
		expect(await api('/api/x', { method: 'POST', json: { a: 1 } })).toEqual({ ok: true });
		const [url, init] = (deps.fetch as any).mock.calls[0];
		expect(url).toBe('/api/x');
		expect(init.headers.Authorization).toBe('Bearer tok');
		expect(init.headers['Content-Type']).toBe('application/json');
		expect(init.body).toBe('{"a":1}');
	});

	it('401 logs out and goes to the login page', async () => {
		auth.set('tok', 'a@b.de');
		deps.fetch = vi.fn(async () =>
			res(
				401,
				{ title: 'Unauthorized', status: 401, detail: 'Bitte melde dich an.' },
				'application/problem+json'
			)
		);
		await expect(api('/api/overview')).rejects.toBeInstanceOf(ApiError);
		expect(auth.token).toBeNull();
		expect(deps.goto).toHaveBeenCalledWith('/login');
	});

	it('401 for a stale token does not wipe the token another tab stored', async () => {
		auth.set('old', 'a@b.de');
		deps.fetch = vi.fn(async () => {
			auth.set('new', 'a@b.de'); // another tab / the password change stored a new token meanwhile
			return res(401, { status: 401, detail: 'Bitte melde dich an.' }, 'application/problem+json');
		});
		await expect(api('/api/overview')).rejects.toBeInstanceOf(ApiError);
		expect(auth.token).toBe('new');
		expect(localStorage.getItem('redefluss.token')).toBe('new');
		expect(deps.goto).not.toHaveBeenCalled();
	});

	it('401 keeps a newer token that only another tab persisted', async () => {
		auth.set('old', 'a@b.de');
		deps.fetch = vi.fn(async () => {
			localStorage.setItem('redefluss.token', 'new');
			return res(401, { status: 401, detail: 'x' }, 'application/problem+json');
		});
		await expect(api('/api/overview')).rejects.toBeInstanceOf(ApiError);
		expect(localStorage.getItem('redefluss.token')).toBe('new');
		expect(deps.goto).not.toHaveBeenCalled();
	});

	it('a failed login (401 on /api/auth/login) does not redirect', async () => {
		deps.fetch = vi.fn(async () =>
			res(
				401,
				{ status: 401, detail: 'E-Mail oder Passwort ist falsch.' },
				'application/problem+json'
			)
		);
		await expect(api('/api/auth/login', { method: 'POST', json: {} })).rejects.toThrow(
			'E-Mail oder Passwort ist falsch.'
		);
		expect(deps.goto).not.toHaveBeenCalled();
	});

	it('uses the problem detail as message and survives non-JSON errors', async () => {
		deps.fetch = vi.fn(async () =>
			res(429, { status: 429, detail: 'Tageslimit erreicht.' }, 'application/problem+json')
		);
		await expect(api('/api/turns')).rejects.toMatchObject({
			status: 429,
			message: 'Tageslimit erreicht.'
		});
		deps.fetch = vi.fn(async () => new Response('<html>Bad Gateway</html>', { status: 502 }));
		await expect(api('/api/turns')).rejects.toMatchObject({
			status: 502,
			message: 'Der Server antwortet gerade nicht. Bitte versuch es gleich noch einmal.'
		});
		deps.fetch = vi.fn(async () => {
			throw new TypeError('Failed to fetch');
		});
		await expect(api('/api/turns')).rejects.toMatchObject({
			status: 0,
			message: 'Keine Verbindung zum Server.'
		});
	});

	it('passes FormData through without a JSON content type and handles 204', async () => {
		deps.fetch = vi.fn(async () => new Response(null, { status: 204 }));
		const form = new FormData();
		expect(await api('/api/turns', { method: 'POST', body: form })).toBeUndefined();
		const [, init] = (deps.fetch as any).mock.calls[0];
		expect(init.headers['Content-Type']).toBeUndefined();
		expect(init.body).toBe(form);
	});

	it('turns a non-JSON 2xx body into an ApiError', async () => {
		deps.fetch = vi.fn(async () => new Response('<!doctype html><html></html>', { status: 200 }));
		await expect(api('/api/unknown')).rejects.toMatchObject({
			status: 200,
			message: 'Unerwartete Antwort vom Server.'
		});
	});
	it('apiBlob fetches binary data with the bearer token', async () => {
		auth.set('tok', 'a@b.de');
		deps.fetch = vi.fn(async () => new Response('mp3', { status: 200, headers: { 'Content-Type': 'audio/mpeg' } }));
		const blob = await apiBlob('/api/vocab/cards/7/audio');
		expect(blob.size).toBe(3);
		const [url, init] = (deps.fetch as any).mock.calls[0];
		expect(url).toBe('/api/vocab/cards/7/audio');
		expect(init.headers.Authorization).toBe('Bearer tok');
		expect(init.headers.Accept).not.toBe('application/json');
		deps.fetch = vi.fn(async () => res(404, { status: 404, detail: 'Karte nicht gefunden.' }, 'application/problem+json'));
		await expect(apiBlob('/api/vocab/cards/8/audio')).rejects.toMatchObject({ status: 404, message: 'Karte nicht gefunden.' });
	});
	it('apiBlob can POST JSON (e.g. speak a sentence)', async () => {
		auth.set('tok', 'a@b.de');
		deps.fetch = vi.fn(async () => new Response('mp3', { status: 200, headers: { 'Content-Type': 'audio/mpeg' } }));
		const blob = await apiBlob('/api/pronunciation/speak', { method: 'POST', json: { text: 'Hallo', slow: true } });
		expect(blob.size).toBe(3);
		const [, init] = (deps.fetch as any).mock.calls[0];
		expect(init.method).toBe('POST');
		expect(init.headers['Content-Type']).toBe('application/json');
		expect(init.headers.Authorization).toBe('Bearer tok');
		expect(JSON.parse(init.body)).toEqual({ text: 'Hallo', slow: true });
	});
});
