// The chart: the harbours as dots on a map wider than the screen, scrolled so the ship's harbour is in the middle.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { chart: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const geometry = require('../util/geometry.js');

  const SCALE = 0.5;

  // Dots in screen pixels for a chart [width] wide centred on [at].
  function dots(harbours, at, width) {
    const pts = geometry.scale(harbours.map(h => ({ x: h.x, y: h.y })), SCALE);
    const here = { x: Math.round(at.x * SCALE), y: Math.round(at.y * SCALE) };
    const shift = geometry.centre(width, 2 * here.x);
    return harbours.map((h, i) => ({ id: h.id, name: h.name, x: pts[i].x + shift, y: pts[i].y, here: h.id === at.id }));
  }

  function draw(canvas, harbours, at) {
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    for (const d of dots(harbours, at, canvas.width)) {
      ctx.fillStyle = d.here ? '#c0392b' : '#2f6f8f';
      ctx.beginPath();
      ctx.arc(d.x, d.y, d.here ? 6 : 4, 0, Math.PI * 2);
      ctx.fill();
    }
  }

  return { SCALE, dots, draw };
});
