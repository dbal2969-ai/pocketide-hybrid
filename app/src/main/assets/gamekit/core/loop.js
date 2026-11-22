// Fixed-timestep game loop: update runs at a steady rate, render runs every frame. Pauses while the page is hidden.
// Part of PocketIDE's game kit; MIT licensed.
export class Loop {
  constructor(update, render, { step = 1 / 60, maxFrame = 0.25 } = {}) {
    this.update = update; this.render = render; this.step = step; this.maxFrame = maxFrame;
    this.acc = 0; this.time = 0; this.running = false; this.last = 0; this.paused = false;
    document.addEventListener('visibilitychange', () => { this.last = performance.now(); });
  }

  start() {
    if (this.running) return;
    this.running = true; this.last = performance.now();
    const tick = now => {
      if (!this.running) return;
      const dt = Math.min((now - this.last) / 1000, this.maxFrame);
      this.last = now;
      if (!this.paused) {
        this.acc += dt;
        while (this.acc >= this.step) { this.update(this.step); this.acc -= this.step; this.time += this.step; }
      }
      this.render(this.acc / this.step);
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  }

  stop() { this.running = false; }
}
