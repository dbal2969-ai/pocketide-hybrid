// Small geometry helpers shared by the layout and the chart.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { geometry: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';

  // The offset that centres something [inner] wide in something [outer] wide. Negative when it is wider: the chart uses
  // that to scroll a map larger than the screen.
  function centre(outer, inner) {
    return Math.round((outer - inner) / 2);
  }

  function clamp(v, lo, hi) {
    return Math.min(hi, Math.max(lo, v));
  }

  function scale(points, factor) {
    return points.map(p => ({ x: Math.round(p.x * factor), y: Math.round(p.y * factor) }));
  }

  function bounds(points) {
    if (!points.length) return { x: 0, y: 0, w: 0, h: 0 };
    const xs = points.map(p => p.x), ys = points.map(p => p.y);
    const x = Math.min(...xs), y = Math.min(...ys);
    return { x, y, w: Math.max(...xs) - x, h: Math.max(...ys) - y };
  }

  return { centre, clamp, scale, bounds };
});
