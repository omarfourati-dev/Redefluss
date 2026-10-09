<script lang="ts">
  let { disabled = false, recording = false, seconds = 0, onPress, onRelease }:
    { disabled?: boolean; recording?: boolean; seconds?: number; onPress: () => void; onRelease: () => void } = $props();

  function key(e: KeyboardEvent, down: boolean) {
    if (e.key !== ' ' && e.key !== 'Enter') return;
    e.preventDefault();
    if (down && !e.repeat) onPress(); else if (!down) onRelease();
  }
</script>

<button type="button" {disabled}
  class="flex h-36 w-36 select-none flex-col items-center justify-center rounded-full text-white shadow-lg transition
         {recording ? 'scale-105 bg-red-600' : 'bg-brand-700 hover:bg-brand-800'} disabled:opacity-50"
  style="touch-action: none; -webkit-user-select: none; -webkit-touch-callout: none;"
  onpointerdown={(e) => { (e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId); onPress(); }}
  onpointerup={onRelease} onpointercancel={onRelease}
  onkeydown={(e) => key(e, true)} onkeyup={(e) => key(e, false)}
  oncontextmenu={(e) => e.preventDefault()}>
  <span class="text-4xl" aria-hidden="true">🎙️</span>
  <span class="mt-1 text-sm font-semibold">
    {#if recording}Ich höre zu … {Math.floor(seconds / 60)}:{String(seconds % 60).padStart(2, '0')}{:else}Halten und sprechen{/if}
  </span>
</button>
