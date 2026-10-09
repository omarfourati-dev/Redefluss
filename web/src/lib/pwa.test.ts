import { describe, expect, it, vi } from 'vitest';
import { Pwa } from './pwa.svelte';

class FakeTarget extends EventTarget {}
function fakeWorker(state: string) {
  return Object.assign(new FakeTarget(), { state, postMessage: vi.fn() }) as unknown as ServiceWorker & { state: string; postMessage: ReturnType<typeof vi.fn> };
}
function setup(opts: { controller: boolean; waiting?: boolean }) {
  const reg = Object.assign(new FakeTarget(), { waiting: opts.waiting ? fakeWorker('installed') : null, installing: null as ServiceWorker | null });
  const container = Object.assign(new FakeTarget(), { controller: opts.controller ? fakeWorker('activated') : null, register: vi.fn(async () => reg) });
  const reload = vi.fn();
  return { reg, container, reload, pwa: new Pwa(container as unknown as ServiceWorkerContainer, reload) };
}

describe('Pwa', () => {
  it('registers /sw.js for the whole app', async () => {
    const { container, pwa } = setup({ controller: false });
    await pwa.register();
    expect(container.register).toHaveBeenCalledWith('/sw.js', { scope: '/' });
    expect(pwa.updateReady).toBe(false);
  });
  it('offers an update when a worker is waiting', async () => {
    const { pwa } = setup({ controller: true, waiting: true });
    await pwa.register();
    expect(pwa.updateReady).toBe(true);
  });
  it('offers an update after a new worker installed, not on the first install', async () => {
    for (const controller of [true, false]) {
      const { reg, pwa } = setup({ controller });
      await pwa.register();
      const w = fakeWorker('installing');
      reg.installing = w;
      reg.dispatchEvent(new Event('updatefound'));
      w.state = 'installed';
      w.dispatchEvent(new Event('statechange'));
      expect(pwa.updateReady).toBe(controller);
    }
  });
  it('reloads once after the user applied the update', async () => {
    const { reg, container, reload, pwa } = setup({ controller: true, waiting: true });
    await pwa.register();
    container.dispatchEvent(new Event('controllerchange'));
    expect(reload).not.toHaveBeenCalled();
    pwa.applyUpdate();
    expect(reg.waiting!.postMessage).toHaveBeenCalledWith('skip-waiting');
    container.dispatchEvent(new Event('controllerchange'));
    container.dispatchEvent(new Event('controllerchange'));
    expect(reload).toHaveBeenCalledTimes(1);
  });
  it('does nothing without service worker support', async () => {
    const pwa = new Pwa(undefined, vi.fn());
    await pwa.register();
    expect(pwa.updateReady).toBe(false);
  });
});
