// Charts as SVG, with no library: line, bar, donut and sparkline. They scale to their box and follow the theme colours.
//   el.append(lineChart({ labels: ['Jan', 'Feb'], series: [{ name: 'Sales', values: [3, 5] }] }))
//   barChart({ labels, values }) · donutChart({ labels, values }) · sparkline(values)
// Each data point has a <title>, so a long press or hover shows its value. Part of PocketIDE's app kit; MIT licensed.
const NS = 'http://www.w3.org/2000/svg';
export const PALETTE = ['var(--accent)', '#e8833a', '#2bb3a3', '#c8558f', '#8a6be0', '#d9a21b', '#5a9ad8', '#6fae4c'];
const el = (tag, attrs = {}, ...kids) => { const e = document.createElementNS(NS, tag); for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v); for (const c of kids) if (c != null) e.append(c); return e; };
const title = t => { const e = document.createElementNS(NS, 'title'); e.textContent = t; return e; };
const nice = max => { if (max <= 0) return 1; const p = Math.pow(10, Math.floor(Math.log10(max))), m = max / p; return (m <= 1 ? 1 : m <= 2 ? 2 : m <= 5 ? 5 : 10) * p; };
const short = n => Math.abs(n) >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : Math.abs(n) >= 1e3 ? (n / 1e3).toFixed(1) + 'k' : String(Math.round(n * 100) / 100);
const wrap = svg => { const d = document.createElement('div'); d.className = 'chart'; d.append(svg); return d; };

function axes(svg, W, H, pad, max, labels) {
  const top = nice(max);
  for (let i = 0; i <= 4; i++) { const y = pad.t + (H - pad.t - pad.b) * (1 - i / 4); svg.append(el('line', { x1: pad.l, x2: W - pad.r, y1: y, y2: y, stroke: 'var(--line)' }), el('text', { x: pad.l - 6, y: y + 4, 'text-anchor': 'end' }, short(top * i / 4))); }
  const step = Math.max(1, Math.ceil(labels.length / 6)), iw = (W - pad.l - pad.r) / Math.max(1, labels.length);
  labels.forEach((l, i) => { if (i % step === 0) svg.append(el('text', { x: pad.l + iw * (i + 0.5), y: H - 6, 'text-anchor': 'middle' }, String(l).slice(0, 10))); });
  return top;
}

export function lineChart({ labels = [], series = [], height = 220 } = {}) {
  const W = 600, H = height, pad = { l: 44, r: 12, t: 12, b: 24 }, svg = el('svg', { viewBox: `0 0 ${W} ${H}`, role: 'img' });
  const top = axes(svg, W, H, pad, Math.max(0, ...series.flatMap(s => s.values)), labels), iw = (W - pad.l - pad.r) / Math.max(1, labels.length);
  const X = i => pad.l + iw * (i + 0.5), Y = v => pad.t + (H - pad.t - pad.b) * (1 - (v || 0) / top);
  series.forEach((s, k) => {
    const c = s.color || PALETTE[k % PALETTE.length];
    svg.append(el('polyline', { points: s.values.map((v, i) => X(i) + ',' + Y(v)).join(' '), fill: 'none', stroke: c, 'stroke-width': 2.5, 'stroke-linejoin': 'round' }));
    s.values.forEach((v, i) => svg.append(el('circle', { cx: X(i), cy: Y(v), r: 3.5, fill: c }, title((s.name ? s.name + ', ' : '') + labels[i] + ': ' + short(v)))));
  });
  return wrap(svg);
}

export function barChart({ labels = [], values = [], color, height = 220 } = {}) {
  const W = 600, H = height, pad = { l: 44, r: 12, t: 12, b: 24 }, svg = el('svg', { viewBox: `0 0 ${W} ${H}`, role: 'img' });
  const top = axes(svg, W, H, pad, Math.max(0, ...values), labels), iw = (W - pad.l - pad.r) / Math.max(1, labels.length);
  values.forEach((v, i) => { const h = (H - pad.t - pad.b) * (v || 0) / top; svg.append(el('rect', { x: pad.l + iw * i + iw * 0.15, y: H - pad.b - h, width: iw * 0.7, height: Math.max(0, h), rx: 4, fill: color || PALETTE[0] }, title(labels[i] + ': ' + short(v)))); });
  return wrap(svg);
}

export function donutChart({ labels = [], values = [], size = 220 } = {}) {
  const total = values.reduce((a, b) => a + (b || 0), 0) || 1, R = 80, r = 50, cx = 110, cy = 110;
  const svg = el('svg', { viewBox: '0 0 420 220', role: 'img' }); let a0 = -Math.PI / 2;
  values.forEach((v, i) => {
    const a1 = a0 + (v || 0) / total * Math.PI * 2, big = a1 - a0 > Math.PI ? 1 : 0, P = (rad, a) => [cx + rad * Math.cos(a), cy + rad * Math.sin(a)];
    const [x0, y0] = P(R, a0), [x1, y1] = P(R, a1 - 1e-6), [x2, y2] = P(r, a1 - 1e-6), [x3, y3] = P(r, a0);
    svg.append(el('path', { d: `M${x0} ${y0}A${R} ${R} 0 ${big} 1 ${x1} ${y1}L${x2} ${y2}A${r} ${r} 0 ${big} 0 ${x3} ${y3}Z`, fill: PALETTE[i % PALETTE.length] }, title(labels[i] + ': ' + short(v) + ' (' + Math.round(v / total * 100) + '%)')));
    a0 = a1;
  });
  svg.append(el('text', { x: cx, y: cy + 5, 'text-anchor': 'middle', style: 'font-size:16px;font-weight:700;fill:var(--text)' }, short(total)));
  labels.slice(0, 8).forEach((l, i) => svg.append(el('rect', { x: 230, y: 30 + i * 22, width: 12, height: 12, rx: 3, fill: PALETTE[i % PALETTE.length] }), el('text', { x: 248, y: 40 + i * 22, style: 'font-size:13px;fill:var(--text)' }, String(l).slice(0, 18) + ' · ' + Math.round((values[i] || 0) / total * 100) + '%')));
  return wrap(svg);
}

export function sparkline(values = [], { color = 'var(--accent)', height = 32 } = {}) {
  const W = 120, H = height, max = Math.max(...values, 1), min = Math.min(...values, 0);
  const pts = values.map((v, i) => (i / Math.max(1, values.length - 1)) * W + ',' + (H - 2 - (v - min) / Math.max(1e-9, max - min) * (H - 4))).join(' ');
  return el('svg', { viewBox: `0 0 ${W} ${H}`, width: W, height: H }, el('polyline', { points: pts, fill: 'none', stroke: color, 'stroke-width': 2 }));
}
