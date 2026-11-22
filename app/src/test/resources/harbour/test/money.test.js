const test = require('node:test');
const assert = require('assert');
const { formatMoney, parseMoney, percentOf, sumMoney } = require('../util/money.js');
const format = require('../util/format.js');

test('money formats with two decimals and thousands separators', () => {
  assert.strictEqual(formatMoney(1250.5), '1,250.50');
  assert.strictEqual(formatMoney(0.5), '0.50');
  assert.strictEqual(formatMoney(-3), '-3.00');
});

test('money parses what the data files hold', () => {
  assert.strictEqual(parseMoney('1,250.50'), 1250.5);
  assert.ok(Number.isNaN(parseMoney('abc')));
});

test('percentages and sums round to cents', () => {
  assert.strictEqual(percentOf(10, 20), 2);
  assert.strictEqual(sumMoney([0.1, 0.2]), 0.3);
});

test('labels', () => {
  assert.strictEqual(format.days(1), '1 day');
  assert.strictEqual(format.days(Infinity), 'cannot sail');
  assert.strictEqual(format.plural(2, 'barrel'), '2 barrels');
});
