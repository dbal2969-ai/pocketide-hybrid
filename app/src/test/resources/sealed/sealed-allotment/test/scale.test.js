const test = require('node:test');
const assert = require('assert');
const { mapWidth, factor } = require('../lib/scale.js');
const { labels } = require('../ui/plotLabels.js');

test('the map is never wider than 800 px', () => {
  assert.strictEqual(mapWidth(1200), 800);
  assert.strictEqual(factor(400), 0.5);
});

test('labels stay inside the map on a computer', () => {
  for (const l of labels([{ name: 'Plot 9', holder: 'Hill', x: 760, y: 0, w: 30 }], 1200)) assert.ok(l.x + l.w <= 800);
});
