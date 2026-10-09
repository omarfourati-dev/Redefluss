/** Installable app: registers /sw.js and reports when a new version waits; the layout offers "Neu laden". */
export class Pwa {
  updateReady = $state(false);
  offline = $state(globalThis.navigator?.onLine === false);
  private registration: ServiceWorkerRegistration | null = null;
  private requested = false;
  private reloading = false;

  constructor(private container: ServiceWorkerContainer | undefined, private reload: () => void) {
    globalThis.addEventListener?.('online', () => (this.offline = false));
    globalThis.addEventListener?.('offline', () => (this.offline = true));
  }

  async register(): Promise<void> {
    if (!this.container) return;
    const reg = await this.container.register('/sw.js', { scope: '/' });
    this.registration = reg;
    // no controller yet = first install: the page already runs the newest code
    const hasController = () => this.container?.controller != null;
    if (reg.waiting && hasController()) this.updateReady = true;
    reg.addEventListener('updatefound', () => {
      const worker = reg.installing;
      worker?.addEventListener('statechange', () => { if (worker.state === 'installed' && hasController()) this.updateReady = true; });
    });
    this.container.addEventListener('controllerchange', () => {
      // the first install also changes the controller (clients.claim) – only reload when the user asked
      if (!this.requested || this.reloading) return;
      this.reloading = true;
      this.reload();
    });
  }

  applyUpdate(): void {
    this.requested = true;
    this.registration?.waiting?.postMessage('skip-waiting');
  }
}

export const pwa = new Pwa(globalThis.navigator?.serviceWorker, () => location.reload());
