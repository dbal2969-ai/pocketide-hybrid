// Sound effects and looping music with the browser's audio. Browsers only allow sound after a tap, so the first tap unlocks it.
//   const sound = new Sound(); await sound.load('coin', 'assets/coin.wav'); sound.play('coin'); sound.music('theme');
// Missing files are skipped silently, so a game runs without sound assets. Make WAV files with game_assets action=sound.
// Part of PocketIDE's game kit; MIT licensed.
export class Sound {
  constructor() {
    this.ctx = null; this.buffers = {}; this.muted = false; this.musicNode = null; this.volume = 1;
    const unlock = () => { this.context(); if (this.ctx && this.ctx.state === 'suspended') this.ctx.resume(); };
    for (const ev of ['pointerdown', 'keydown']) addEventListener(ev, unlock, { passive: true });
  }

  context() {
    if (!this.ctx) { const C = window.AudioContext || window.webkitAudioContext; if (C) this.ctx = new C(); }
    return this.ctx;
  }

  async load(name, url) {
    try {
      const ctx = this.context(); if (!ctx) return false;
      const r = await fetch(url); if (!r.ok) return false;
      this.buffers[name] = await ctx.decodeAudioData(await r.arrayBuffer());
      return true;
    } catch (e) { return false; }
  }

  play(name, volume = 1, rate = 1) {
    const b = this.buffers[name]; if (!b || this.muted || !this.ctx) return;
    const src = this.ctx.createBufferSource(), gain = this.ctx.createGain();
    src.buffer = b; src.playbackRate.value = rate; gain.gain.value = volume * this.volume;
    src.connect(gain); gain.connect(this.ctx.destination); src.start();
  }

  music(name, volume = 0.5) {
    this.stopMusic();
    const b = this.buffers[name]; if (!b || !this.ctx) return;
    const src = this.ctx.createBufferSource(), gain = this.ctx.createGain();
    src.buffer = b; src.loop = true; gain.gain.value = this.muted ? 0 : volume * this.volume;
    src.connect(gain); gain.connect(this.ctx.destination); src.start();
    this.musicNode = { src, gain, volume };
  }

  stopMusic() { if (this.musicNode) { try { this.musicNode.src.stop(); } catch (e) { /* already stopped */ } this.musicNode = null; } }

  mute(on) { this.muted = on; if (this.musicNode) this.musicNode.gain.gain.value = on ? 0 : this.musicNode.volume * this.volume; }
}
