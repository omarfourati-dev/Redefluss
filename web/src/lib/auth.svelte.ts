const TOKEN = 'redefluss.token';
const EMAIL = 'redefluss.email';

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

class Auth {
	token = $state<string | null>(read(TOKEN));
	email = $state<string | null>(read(EMAIL));
	set(token: string, email: string) {
		this.token = token;
		this.email = email;
		write(TOKEN, token);
		write(EMAIL, email);
	}
	/** The token as persisted (another tab may have replaced it). */
	stored(): string | null {
		return read(TOKEN);
	}
	clear() {
		this.token = null;
		this.email = null;
		write(TOKEN, null);
		write(EMAIL, null);
	}
}

export const auth = new Auth();
