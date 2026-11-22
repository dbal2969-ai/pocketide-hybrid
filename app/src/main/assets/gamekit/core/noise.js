// Smooth random noise for terrain, caves and placement: the same seed always gives the same world.
//   const n = new Noise(42); n.at(x, z) -> about -1..1 (smooth); n.fbm(x, z, 4) -> layered detail; rng(seed) -> a seeded random()
// Part of PocketIDE's game kit; MIT licensed.

// A small seeded random number generator (mulberry32): rng(seed)() -> 0..1
export function rng(seed) {
  let a = (seed >>> 0) || 1;
  return () => { a |= 0; a = a + 0x6D2B79F5 | 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; };
}

// a whole-number seed from text ("Skyrim-like" -> a number)
export function seedOf(text) { let h = 2166136261; for (const c of String(text)) { h ^= c.charCodeAt(0); h = Math.imul(h, 16777619); } return h >>> 0; }

export class Noise {
  constructor(seed = 1) {
    const r = rng(seed); this.p = new Uint8Array(512); const perm = [...Array(256).keys()];
    for (let i = 255; i > 0; i--) { const j = Math.floor(r() * (i + 1)); [perm[i], perm[j]] = [perm[j], perm[i]]; }
    for (let i = 0; i < 512; i++) this.p[i] = perm[i & 255];
    this.g = []; for (let i = 0; i < 256; i++) { const a = r() * Math.PI * 2; this.g.push([Math.cos(a), Math.sin(a)]); }
  }
  // gradient noise in 2D, about -1..1
  at(x, z) {
    const xi = Math.floor(x), zi = Math.floor(z), xf = x - xi, zf = z - zi;
    const fade = t => t * t * t * (t * (t * 6 - 15) + 10);
    const dot = (ix, iz, dx, dz) => { const g = this.g[this.p[(this.p[ix & 255] + iz) & 511] & 255]; return g[0] * dx + g[1] * dz; };
    const u = fade(xf), v = fade(zf);
    const a = dot(xi, zi, xf, zf), b = dot(xi + 1, zi, xf - 1, zf), c = dot(xi, zi + 1, xf, zf - 1), d = dot(xi + 1, zi + 1, xf - 1, zf - 1);
    return 1.41 * ((a + u * (b - a)) + v * ((c + u * (d - c)) - (a + u * (b - a))));
  }
  // several layers of noise (octaves), each half as strong and twice as fine
  fbm(x, z, octaves = 4) { let s = 0, amp = 1, f = 1, norm = 0; for (let i = 0; i < octaves; i++) { s += amp * this.at(x * f, z * f); norm += amp; amp *= 0.5; f *= 2; } return s / norm; }
}
