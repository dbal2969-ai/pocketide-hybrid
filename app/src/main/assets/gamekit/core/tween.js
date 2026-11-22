// Tweens and timers: smoothly change numbers on an object over time.
//   const tweens = new Tweens(); tweens.to(sprite, {x: 100, alpha: 0}, 0.5, 'quadOut', () => remove(sprite)); tweens.update(dt);
// Part of PocketIDE's game kit; MIT licensed.
export const ease = {
  linear: t => t,
  quadIn: t => t * t,
  quadOut: t => 1 - (1 - t) * (1 - t),
  quadInOut: t => t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2,
  backOut: t => { const c1 = 1.70158, c3 = c1 + 1; return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2); },
  bounceOut: t => { const n = 7.5625, d = 2.75; if (t < 1 / d) return n * t * t; if (t < 2 / d) return n * (t -= 1.5 / d) * t + 0.75; if (t < 2.5 / d) return n * (t -= 2.25 / d) * t + 0.9375; return n * (t -= 2.625 / d) * t + 0.984375; },
};

export class Tweens {
  constructor() { this.list = []; this.timers = []; }

  to(target, props, seconds, easing = 'quadOut', done = null) {
    const from = {}; for (const k of Object.keys(props)) from[k] = target[k];
    const t = { target, props, from, seconds: Math.max(seconds, 0.0001), t: 0, fn: ease[easing] || ease.linear, done, dead: false };
    this.list.push(t); return t;
  }

  after(seconds, fn) { const t = { left: seconds, fn, dead: false }; this.timers.push(t); return t; }
  cancel(t) { t.dead = true; }

  update(dt) {
    for (const t of this.list) {
      if (t.dead) continue;
      t.t = Math.min(1, t.t + dt / t.seconds); const e = t.fn(t.t);
      for (const k of Object.keys(t.props)) t.target[k] = t.from[k] + (t.props[k] - t.from[k]) * e;
      if (t.t >= 1) { t.dead = true; if (t.done) t.done(); }
    }
    for (const t of this.timers) { if (t.dead) continue; t.left -= dt; if (t.left <= 0) { t.dead = true; t.fn(); } }
    this.list = this.list.filter(t => !t.dead); this.timers = this.timers.filter(t => !t.dead);
  }
}
