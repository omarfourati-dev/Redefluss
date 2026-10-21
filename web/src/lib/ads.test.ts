import { describe, expect, it, vi } from 'vitest';
import { ADSENSE_CLIENT, ADSENSE_HOST, CONSENT_COOKIES, loadAds, resetAdConsent } from './ads';

function scripts() {
	return [...document.head.querySelectorAll('script')].filter((s) => s.src.includes('googlesyndication'));
}

describe('loadAds', () => {
	it('loads nothing locally, in CI or on any other host', () => {
		for (const host of ['localhost', '127.0.0.1', 'app', 'omarfourati.de']) expect(loadAds(document, host)).toBe(false);
		expect(scripts()).toHaveLength(0);
	});

	it('adds the AdSense loader once on the live site', () => {
		expect(loadAds(document, ADSENSE_HOST)).toBe(true);
		expect(loadAds(document, ADSENSE_HOST)).toBe(false);
		const [script] = scripts();
		expect(scripts()).toHaveLength(1);
		expect(script.src).toBe(`https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=${ADSENSE_CLIENT}`);
		expect(script.async).toBe(true);
		expect(script.crossOrigin).toBe('anonymous');
	});
});

describe('resetAdConsent', () => {
	it('expires the consent cookies on the host and every parent domain, then reloads', () => {
		const written: string[] = [];
		const doc = {
			set cookie(v: string) {
				written.push(v);
			},
			get cookie() {
				return '';
			}
		};
		const reload = vi.fn();
		resetAdConsent(doc, { hostname: ADSENSE_HOST, reload });
		for (const name of CONSENT_COOKIES) {
			expect(written).toContain(`${name}=; Max-Age=0; path=/`);
			expect(written).toContain(`${name}=; Max-Age=0; path=/; domain=.redefluss.omarfourati.de`);
			expect(written).toContain(`${name}=; Max-Age=0; path=/; domain=.omarfourati.de`);
		}
		expect(written.some((c) => c.endsWith('domain=.de'))).toBe(false);
		expect(reload).toHaveBeenCalledOnce();
	});
});
