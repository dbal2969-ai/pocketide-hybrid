const test = require('node:test');
const assert = require('assert');
const receipt = require('../ui/receipt.js');
const cart = require('../state/cart.js');
const hud = require('../ui/hud.js');
const cargo = require('../ui/cargo.js');
const voyage = require('../state/voyage.js');
const layout = require('../util/layout.js');
const chart = require('../ui/chart.js');

const rope = { id: 'rope', name: 'Rope', category: 'Gear', price: 1, weight: 1 };
const sloop = { id: 'sloop', name: 'Sloop', capacity: 40, crew: 8, wage: 1.5, speed: 9 };
const here = { id: 'a', name: 'A', region: 'R', tax: 0, x: 0, y: 0, makes: [] };

test('the receipt lists the goods and a total', () => {
  const lines = receipt.lines(cart.add(cart.newCart(), rope, 3), []);
  assert.strictEqual(lines[0], '3 x Rope  3.00');
  assert.strictEqual(lines[lines.length - 1], 'Total  3.00');
});

test('the top bar shows the ship and the crew', () => {
  const v = voyage.newVoyage(sloop, 12.5, here);
  assert.strictEqual(hud.text(v), 'Sloop  ·  day 0  ·  12.50 gold  ·  crew 8 of 8');
});

test('the hold puts the biggest lots first', () => {
  const v = voyage.newVoyage(sloop, 0, here);
  v.hold = { rope: 2, tar: 5 };
  const r = cargo.rows(v, [rope, { id: 'tar', name: 'Tar', weight: 2 }]);
  assert.deepStrictEqual(r.lines, ['5 x Tar', '2 x Rope']);
  assert.strictEqual(r.left, '28 t of 40 t free');
});

test('the ship icon is inside a wide screen', () => {
  assert.ok(layout.iconBox(1000).left > 0);
});

test('the chart puts the ship\'s harbour in the middle', () => {
  const d = chart.dots([here, { id: 'b', name: 'B', x: 400, y: 100 }], here, 360);
  assert.strictEqual(d[0].x, 180);
});
