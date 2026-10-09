import { describe, expect, it } from 'vitest';
import { encodeWav, resampleLinear } from './wav';

const ascii = (v: DataView, at: number, n: number) => String.fromCharCode(...Array.from({ length: n }, (_, i) => v.getUint8(at + i)));

describe('encodeWav', () => {
  it('writes a 44-byte PCM header for 16 kHz mono 16-bit', async () => {
    const blob = encodeWav(new Float32Array(10), 16000);
    expect(blob.type).toBe('audio/wav');
    const v = new DataView(await blob.arrayBuffer());
    expect(v.byteLength).toBe(44 + 20);
    expect(ascii(v, 0, 4)).toBe('RIFF');
    expect(v.getUint32(4, true)).toBe(36 + 20);
    expect(ascii(v, 8, 4)).toBe('WAVE');
    expect(ascii(v, 12, 4)).toBe('fmt ');
    expect(v.getUint32(16, true)).toBe(16);
    expect(v.getUint16(20, true)).toBe(1); // PCM
    expect(v.getUint16(22, true)).toBe(1); // mono
    expect(v.getUint32(24, true)).toBe(16000);
    expect(v.getUint32(28, true)).toBe(32000); // byte rate
    expect(v.getUint16(32, true)).toBe(2); // block align
    expect(v.getUint16(34, true)).toBe(16);
    expect(ascii(v, 36, 4)).toBe('data');
    expect(v.getUint32(40, true)).toBe(20); // 2 × samples
  });

  it('clips samples to [-1, 1] and writes them little-endian', async () => {
    const v = new DataView(await encodeWav(Float32Array.from([0, 1, -1, 2, -3, 0.5]), 16000).arrayBuffer());
    const s = (i: number) => v.getInt16(44 + 2 * i, true);
    expect([s(0), s(1), s(2), s(3), s(4)]).toEqual([0, 32767, -32768, 32767, -32768]);
    expect(s(5)).toBeCloseTo(16383, -1);
    expect([v.getUint8(46), v.getUint8(47)]).toEqual([0xff, 0x7f]); // 32767 little-endian
  });
});

describe('resampleLinear', () => {
  it('48 kHz → 16 kHz keeps ⌊n/3⌋ samples', () => {
    const input = Float32Array.from({ length: 4801 }, (_, i) => i);
    const out = resampleLinear(input, 48000, 16000);
    expect(out.length).toBe(1600);
    expect(out[0]).toBe(0);
    expect(out[1]).toBe(3);
    expect(out[1599]).toBe(4797);
  });

  it('interpolates between neighbouring samples', () => {
    const out = resampleLinear(Float32Array.from([0, 3, 6, 9, 12, 15]), 3, 2);
    expect(Array.from(out)).toEqual([0, 4.5, 9, 13.5]);
  });

  it('same rate returns a copy', () => {
    const input = Float32Array.from([0.1, 0.2]);
    const out = resampleLinear(input, 16000, 16000);
    expect(Array.from(out)).toEqual(Array.from(input));
    expect(out).not.toBe(input);
  });
});
