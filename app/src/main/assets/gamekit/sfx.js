// Sounds made in code (no audio files): chop, hit, miss, level up, coin, click, and a quiet looping tune of plain notes.
// Browsers only start audio after a tap, so the first tap or key press unlocks it. The cache holds no music, so the tune is
// a simple made-up one. Written for PocketIDE's game kit; MIT licensed.

const NOTE = n => 440 * Math.pow(2, (n - 69) / 12);   // midi note number to Hz
// the tune: a slow pentatonic phrase, one note per entry, 0 = rest; each lasts 0.45 s
const TUNE = [60, 0, 64, 67, 0, 72, 69, 0, 67, 64, 0, 62, 60, 0, 0, 0, 62, 0, 65, 69, 0, 72, 71, 0, 67, 65, 0, 64, 62, 0, 0, 0];

export class Sfx {
  // settings: { volume: 0..1, muted: bool } (read from and written back to the saved game by the caller)
  constructor(settings = {}) {
    this.volume = typeof settings.volume === 'number' ? settings.volume : 0.5;
    this.muted = !!settings.muted;
    this.ctx = null; this.noise = null; this.played = {};   // played: how many times each sound was asked for (handy for tests)
    this.musicOn = false; this.musicTimer = null; this.step = 0; this.master = null;
    this.onChange = () => {};
    const unlock = () => { this.unlock(); };
    for (const ev of ['pointerdown', 'keydown']) addEventListener(ev, unlock, { passive: true });
  }
  get ready() { return !!this.ctx && this.ctx.state === 'running'; }

  unlock() {
    if (!this.ctx) {
      const C = window.AudioContext || window.webkitAudioContext; if (!C) return false;
      try { this.ctx = new C(); } catch (e) { return false; }
      this.master = this.ctx.createGain(); this.master.gain.value = this.muted ? 0 : this.volume; this.master.connect(this.ctx.destination);
      const len = this.ctx.sampleRate * 0.4, buf = this.ctx.createBuffer(1, len, this.ctx.sampleRate), d = buf.getChannelData(0);
      for (let i = 0; i < len; i++) d[i] = Math.random() * 2 - 1;
      this.noise = buf;
    }
    if (this.ctx.state === 'suspended') this.ctx.resume();
    if (!this.musicOn) this.music(true);
    return true;
  }

  setVolume(v) { this.volume = Math.max(0, Math.min(1, v)); this.apply(); }
  setMuted(m) { this.muted = !!m; this.apply(); }
  apply() { if (this.master) this.master.gain.value = this.muted ? 0 : this.volume; this.onChange(); }
  toJSON() { return { volume: this.volume, muted: this.muted }; }

  // a tone: frequency (Hz or [from, to]), seconds, wave shape, loudness
  tone(freq, dur, type = 'sine', vol = 0.3, at = 0) {
    const c = this.ctx; if (!c) return;
    const t0 = c.currentTime + at, o = c.createOscillator(), g = c.createGain();
    o.type = type;
    if (Array.isArray(freq)) { o.frequency.setValueAtTime(freq[0], t0); o.frequency.exponentialRampToValueAtTime(Math.max(1, freq[1]), t0 + dur); } else o.frequency.value = freq;
    g.gain.setValueAtTime(0.0001, t0); g.gain.exponentialRampToValueAtTime(vol, t0 + 0.01); g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
    o.connect(g); g.connect(this.master); o.start(t0); o.stop(t0 + dur + 0.02);
  }
  burst(dur, vol = 0.3, cutoff = 2000, at = 0) {
    const c = this.ctx; if (!c || !this.noise) return;
    const t0 = c.currentTime + at, s = c.createBufferSource(), f = c.createBiquadFilter(), g = c.createGain();
    s.buffer = this.noise; f.type = 'lowpass'; f.frequency.value = cutoff;
    g.gain.setValueAtTime(vol, t0); g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
    s.connect(f); f.connect(g); g.connect(this.master); s.start(t0); s.stop(t0 + dur + 0.02);
  }

  play(name) {
    this.played[name] = (this.played[name] || 0) + 1;
    if (!this.ctx || this.muted) return;
    switch (name) {
      case 'chop': this.burst(0.09, 0.5, 1800); this.tone([220, 90], 0.12, 'square', 0.15); break;
      case 'hit': this.burst(0.12, 0.6, 900); this.tone([160, 70], 0.14, 'sawtooth', 0.2); break;
      case 'miss': this.burst(0.18, 0.25, 3500); break;
      case 'levelup': [72, 76, 79, 84].forEach((n, i) => this.tone(NOTE(n), 0.22, 'triangle', 0.3, i * 0.12)); break;
      case 'coin': this.tone(NOTE(88), 0.08, 'square', 0.12); this.tone(NOTE(95), 0.18, 'square', 0.12, 0.07); break;
      case 'click': this.tone(900, 0.04, 'square', 0.08); break;
      default: break;
    }
  }

  // the looping tune, quietly under everything else
  music(on) {
    this.musicOn = on;
    clearInterval(this.musicTimer); this.musicTimer = null;
    if (!on || !this.ctx) return;
    const next = () => {
      const n = TUNE[this.step++ % TUNE.length];
      if (n && !this.muted) this.tone(NOTE(n), 0.9, 'triangle', 0.05);
    };
    next(); this.musicTimer = setInterval(next, 450);
  }
}
