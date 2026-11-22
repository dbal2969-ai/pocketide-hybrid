// Save and load game data as JSON in slots. Uses the browser's local storage when it works and keeps the data in memory when it does not
// (private windows, blocked storage), so a game never crashes because saving is unavailable.
//   const save = new Save('mygame'); save.write('main', {level: 3}); const data = save.read('main', {level: 1});
// Part of PocketIDE's game kit; MIT licensed.
export class Save {
  constructor(name, version = 1) { this.name = name; this.version = version; this.memory = {}; }

  key(slot) { return 'save:' + this.name + ':' + slot; }

  write(slot, data) {
    const text = JSON.stringify({ v: this.version, at: Date.now(), data });
    this.memory[slot] = text;
    try { localStorage.setItem(this.key(slot), text); return true; } catch (e) { return false; }
  }

  // Returns the saved data, or fallback when there is none (or it was saved by another version of the game).
  read(slot, fallback = null) {
    let text = null;
    try { text = localStorage.getItem(this.key(slot)); } catch (e) { /* storage blocked */ }
    if (text == null) text = this.memory[slot] || null;
    if (text == null) return fallback;
    try { const s = JSON.parse(text); return s.v === this.version ? s.data : fallback; } catch (e) { return fallback; }
  }

  has(slot) { return this.read(slot, undefined) !== undefined; }
  remove(slot) { delete this.memory[slot]; try { localStorage.removeItem(this.key(slot)); } catch (e) { /* ignore */ } }
}
