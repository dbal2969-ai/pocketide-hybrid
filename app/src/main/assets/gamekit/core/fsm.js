// A small state machine for game screens and character behaviour.
//   const fsm = new StateMachine({ menu: {enter(){}, update(dt){}, draw(ctx){}, exit(){}}, play: {...} }, 'menu');
//   fsm.go('play'); fsm.update(dt); fsm.draw(ctx);
// Part of PocketIDE's game kit; MIT licensed.
export class StateMachine {
  constructor(states, start) { this.states = states; this.name = null; this.time = 0; if (start) this.go(start); }

  go(name, data) {
    if (!this.states[name]) throw new Error('unknown state ' + name);
    const old = this.states[this.name]; if (old && old.exit) old.exit(name);
    const prev = this.name; this.name = name; this.time = 0;
    const s = this.states[name]; if (s.enter) s.enter(data, prev);
  }

  get state() { return this.states[this.name]; }
  update(dt) { this.time += dt; const s = this.state; if (s && s.update) s.update(dt); }
  draw(ctx) { const s = this.state; if (s && s.draw) s.draw(ctx); }
}
