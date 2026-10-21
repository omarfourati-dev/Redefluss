// Google AdSense (auto ads plus Google's own consent message from "Privacy & messaging"), only on the live site:
// never locally, in CI or in tests, so the fake stack stays free of third-party scripts. Privacy notice:
// omarfourati.de/de/datenschutz §6.
export const ADSENSE_CLIENT = 'ca-pub-4072840905222883';
export const ADSENSE_HOST = 'redefluss.omarfourati.de';
export const PRIVACY_URL = 'https://omarfourati.de/de/datenschutz';
const SCRIPT_ID = 'adsbygoogle-loader';

/** Adds the AdSense loader once; returns whether it was added. */
export function loadAds(doc: Document = document, host: string = location.hostname): boolean {
	if (host !== ADSENSE_HOST || doc.getElementById(SCRIPT_ID)) return false;
	const script = doc.createElement('script');
	script.id = SCRIPT_ID;
	script.async = true;
	script.crossOrigin = 'anonymous';
	script.src = `https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=${ADSENSE_CLIENT}`;
	doc.head.appendChild(script);
	return true;
}

/** Cookies in which Google's consent message stores the decision. */
export const CONSENT_COOKIES = ['FCCDCF', 'FCNEC'];

/**
 * "Datenschutzeinstellungen": forgets the stored consent decision (on this host and every parent domain) and reloads,
 * so Google's consent message appears again like on a first visit. googlefc.showRevocationMessage() was not reliable.
 */
export function resetAdConsent(
	doc: { cookie: string } = document,
	loc: { hostname: string; reload(): void } = location
): void {
	const parts = loc.hostname.split('.');
	const domains = [''];
	for (let k = 0; k < parts.length - 1; k++) domains.push(`; domain=.${parts.slice(k).join('.')}`);
	for (const name of CONSENT_COOKIES) for (const d of domains) doc.cookie = `${name}=; Max-Age=0; path=/${d}`;
	loc.reload();
}
