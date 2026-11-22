const test = require('node:test');
const assert = require('assert');
const { index } = require('../lib/prices.js');
const { costPerServing, totalCost } = require('../lib/cost.js');
const { money } = require('../lib/format.js');

const prices = index([{ id: 'flour', name: 'flour', unit: 'g', pricePerUnit: 0.001 }, { id: 'eggs', name: 'eggs', unit: 'each', pricePerUnit: 0.3 }]);

test('a one-batch recipe costs its ingredients', () => {
  const r = { id: 'r', name: 'R', servings: 4, items: [{ id: 'flour', qty: 200 }, { id: 'eggs', qty: 2 }] };
  assert.strictEqual(Math.round(totalCost(r, prices) * 100), 80);
  assert.strictEqual(Math.round(costPerServing(r, prices) * 100), 20);
});

test('money shows the symbol and two decimals', () => {
  assert.strictEqual(money(1.5), '\u00a31.50');
  assert.strictEqual(money(0.31, { roundTo5p: true }), '\u00a30.35');
});
