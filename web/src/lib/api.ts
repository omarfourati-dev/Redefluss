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
	const headers: Record<string, string> = {
		Accept: 'application/json',
		...(init.headers as Record<string, string>)
	};
	if (auth.token) headers.Authorization = `Bearer ${auth.token}`;
	let body = init.body;
	if (init.json !== undefined) {
		headers['Content-Type'] = 'application/json';
		body = JSON.stringify(init.json);
	}
	let res: Response;
	try {
		res = await deps.fetch(path, { ...init, headers, body });
	} catch {
		throw new ApiError(0, 'Keine Verbindung zum Server.');
	}
	if (res.status === 401 && path !== '/api/auth/login') {
		auth.clear();
		void deps.goto('/login');
	}
	if (!res.ok) throw new ApiError(res.status, await problemDetail(res));
	if (res.status === 204) return undefined as T;
	return (await res.json()) as T;
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
