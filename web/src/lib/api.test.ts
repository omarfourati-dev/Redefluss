import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, deps } from './api';
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
});
