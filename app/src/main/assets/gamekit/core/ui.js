// Canvas user interface: text, buttons, bars and a dialogue box. All sizes are canvas pixels; hit-test taps with button.hit(x, y).
// Part of PocketIDE's game kit; MIT licensed.
export function text(ctx, str, x, y, { size = 16, color = '#fff', align = 'left', baseline = 'top', shadow = true, font = 'sans-serif', bold = false } = {}) {
  ctx.save(); ctx.font = (bold ? 'bold ' : '') + size + 'px ' + font; ctx.textAlign = align; ctx.textBaseline = baseline;
  if (shadow) { ctx.fillStyle = 'rgba(0,0,0,0.6)'; ctx.fillText(str, x + 1, y + 1); }
  ctx.fillStyle = color; ctx.fillText(str, x, y); ctx.restore();
}

export function wrap(ctx, str, maxWidth) {
  const lines = []; let line = '';
  for (const word of String(str).split(/\s+/)) {
    const test = line ? line + ' ' + word : word;
    if (ctx.measureText(test).width > maxWidth && line) { lines.push(line); line = word; } else line = test;
  }
  if (line) lines.push(line); return lines;
}

export function bar(ctx, x, y, w, h, fraction, { color = '#4c4', back = '#411', border = '#000' } = {}) {
  ctx.fillStyle = back; ctx.fillRect(x, y, w, h);
  ctx.fillStyle = color; ctx.fillRect(x, y, Math.max(0, Math.min(1, fraction)) * w, h);
  ctx.strokeStyle = border; ctx.lineWidth = 1; ctx.strokeRect(x + 0.5, y + 0.5, w - 1, h - 1);
}

export class Button {
  constructor(label, x, y, w, h, onTap) { Object.assign(this, { label, x, y, w, h, onTap }); }
  hit(px, py) { return px >= this.x && px <= this.x + this.w && py >= this.y && py <= this.y + this.h; }
  tap(px, py) { if (this.hit(px, py)) { if (this.onTap) this.onTap(); return true; } return false; }
  draw(ctx) {
    ctx.fillStyle = 'rgba(30,30,40,0.85)'; ctx.fillRect(this.x, this.y, this.w, this.h);
    ctx.strokeStyle = '#fff'; ctx.lineWidth = 2; ctx.strokeRect(this.x, this.y, this.w, this.h);
    text(ctx, this.label, this.x + this.w / 2, this.y + this.h / 2, { align: 'center', baseline: 'middle', size: Math.min(20, this.h * 0.5) });
  }
}

// Speech box with typewriter text. say(['line one', 'line two'], 'Name'); tap() advances; draw(ctx, canvas) each frame.
export class Dialogue {
  constructor() { this.lines = []; this.speaker = ''; this.shown = 0; this.active = false; this.onEnd = null; }
  say(lines, speaker = '', onEnd = null) { this.lines = Array.isArray(lines) ? lines.slice() : [lines]; this.speaker = speaker; this.shown = 0; this.active = true; this.onEnd = onEnd; }
  update(dt) { if (this.active && this.lines.length) this.shown = Math.min(this.lines[0].length, this.shown + dt * 45); }
  tap() {
    if (!this.active) return false;
    if (this.shown < this.lines[0].length) { this.shown = this.lines[0].length; return true; }
    this.lines.shift(); this.shown = 0;
    if (!this.lines.length) { this.active = false; if (this.onEnd) this.onEnd(); }
    return true;
  }
  draw(ctx, canvas) {
    if (!this.active || !this.lines.length) return;
    const w = canvas.width - 24, h = 96, x = 12, y = canvas.height - h - 12;
    ctx.fillStyle = 'rgba(15,15,30,0.92)'; ctx.fillRect(x, y, w, h); ctx.strokeStyle = '#fff'; ctx.lineWidth = 2; ctx.strokeRect(x, y, w, h);
    if (this.speaker) text(ctx, this.speaker, x + 12, y + 8, { size: 15, color: '#fd5', bold: true });
    ctx.font = '16px sans-serif';
    const lines = wrap(ctx, this.lines[0].slice(0, Math.floor(this.shown)), w - 24);
    lines.slice(0, 3).forEach((l, i) => text(ctx, l, x + 12, y + 30 + i * 20, { size: 16, shadow: false }));
  }
}
