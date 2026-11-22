const test = require('node:test');
const assert = require('assert');
const { show } = require('../lib/units.js');
const { load } = require('../lib/settings.js');

test('Celsius when Celsius is chosen', () => {
  assert.strictEqual(show(19.5, 'C'), '19.5 \u00b0C');
  assert.strictEqual(show(20, 'F'), '68 \u00b0F');
});

test('the saved unit is used', () => {
  const store = { getItem: () => '{"unit":"C"}' };
  assert.strictEqual(load(store).unit, 'C');
});
