import { goto } from '$app/navigation';
import { auth } from './auth.svelte';

export class ApiError extends Error {
	constructor(
		public status: number,
		message: string
	) {
		super(message);
	}
}

/** Swappable in tests. */
export const deps = {
	fetch: (...a: Parameters<typeof fetch>) => fetch(...a),
	goto: (url: string): unknown => goto(url)
};

export async function api<T>(
	path: string,
	init: RequestInit & { json?: unknown } = {}
): Promise<T> {
	const res = await request(path, init, 'application/json');
	if (res.status === 204) return undefined as T;
	try {
		return (await res.json()) as T;
	} catch {
		// e.g. the SPA fallback's index.html answering an unknown /api path with 200
		throw new ApiError(res.status, 'Unerwartete Antwort vom Server.');
	}
}

/** Binary response (e.g. audio) with the same auth and error handling as {@link api}; GET unless `init` says otherwise. */
export async function apiBlob(
	path: string,
	init: RequestInit & { json?: unknown } = {}
): Promise<Blob> {
	return (await request(path, init, '*/*')).blob();
}

async function request(
	path: string,
	init: RequestInit & { json?: unknown },
	accept: string
): Promise<Response> {
	const { json, ...rest } = init;
	const headers: Record<string, string> = {
		Accept: accept,
		...(rest.headers as Record<string, string>)
	};
	const sent = auth.token;
	if (sent) headers.Authorization = `Bearer ${sent}`;
	let body = rest.body;
	if (json !== undefined) {
		headers['Content-Type'] = 'application/json';
		body = JSON.stringify(json);
	}
	let res: Response;
	try {
		res = await deps.fetch(path, { ...rest, headers, body });
	} catch {
		throw new ApiError(0, 'Keine Verbindung zum Server.');
	}
	if (res.status === 401 && path !== '/api/auth/login') {
		// a stale tab must not wipe a newer token that another tab stored (e.g. after a password change)
		const stored = auth.stored();
		if (auth.token === sent && (stored === null || stored === sent)) {
			auth.clear();
			void deps.goto('/login');
		}
	}
	if (!res.ok) throw new ApiError(res.status, await problemDetail(res));
	return res;
}

async function problemDetail(res: Response): Promise<string> {
	try {
		const p = await res.json();
		if (typeof p?.detail === 'string' && p.detail) return p.detail;
	} catch {
		/* not JSON, e.g. a proxy error page */
	}
	return res.status >= 500
		? 'Der Server antwortet gerade nicht. Bitte versuch es gleich noch einmal.'
		: 'Das hat nicht geklappt.';
}
