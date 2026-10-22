const TOKEN = 'redefluss.token';
const EMAIL = 'redefluss.email';
const ROLE = 'redefluss.role';

export type Role = 'owner' | 'guest';

function read(key: string): string | null {
	try {
		return localStorage.getItem(key);
	} catch {
		return null;
	}
}
function write(key: string, value: string | null) {
	try {
		if (value === null) localStorage.removeItem(key);
		else localStorage.setItem(key, value);
	} catch {
		/* private mode: memory only */
	}
}
function asRole(value: string | null | undefined): Role | null {
	return value === 'owner' || value === 'guest' ? value : null;
}

class Auth {
	token = $state<string | null>(read(TOKEN));
	email = $state<string | null>(read(EMAIL));
	/** owner may invite guests; null until known (older sessions learn it from /api/auth/me). */
	role = $state<Role | null>(asRole(read(ROLE)));
	set(token: string, email: string, role?: string | null) {
		this.token = token;
		this.email = email;
		write(TOKEN, token);
		write(EMAIL, email);
		if (role !== undefined) this.setRole(role);
	}
	setRole(role: string | null) {
		this.role = asRole(role);
		write(ROLE, this.role);
	}
	/** The token as persisted (another tab may have replaced it). */
	stored(): string | null {
		return read(TOKEN);
	}
	clear() {
		this.token = null;
		this.email = null;
		this.role = null;
		write(TOKEN, null);
		write(EMAIL, null);
		write(ROLE, null);
	}
}

export const auth = new Auth();
