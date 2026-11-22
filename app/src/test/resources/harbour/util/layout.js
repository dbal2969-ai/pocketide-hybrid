// Screen layout: the board is designed 420 CSS pixels wide and centred on the screen. layoutVars() gives the CSS variables
// (--edge, --board, --icon) that style.css uses for the HUD, the ship icon and the panels, so they line up with the board.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { layout: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { centre } = require('./geometry.js');

  const BOARD = 420;
  const INSET = 8;
  const ICON = 48;

  function layoutVars(viewport) {
    const edge = centre(viewport, BOARD);
    return { '--edge': edge + 'px', '--board': Math.min(viewport, BOARD) + 'px', '--icon': ICON + 'px' };
  }

  function iconBox(viewport) {
    const edge = parseInt(layoutVars(viewport)['--edge'], 10);
    return { left: edge + INSET, top: INSET, width: ICON, height: ICON };
  }

  function applyLayout(doc, viewport) {
    const v = layoutVars(viewport);
    for (const k of Object.keys(v)) doc.documentElement.style.setProperty(k, v[k]);
    return v;
  }

  return { BOARD, INSET, ICON, layoutVars, iconBox, applyLayout };
});
