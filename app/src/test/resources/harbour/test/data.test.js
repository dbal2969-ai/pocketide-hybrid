const test = require('node:test');
const assert = require('assert');
const { loadSync } = require('../loader/load.js');
const { normaliseHarbour, normaliseOffer, normaliseShips } = require('../loader/normalise.js');
const convert = require('../util/convert.js');

test('the data loads with no problems', () => {
  const data = loadSync();
  assert.deepStrictEqual(data.problems, []);
  assert.ok(data.goods.length >= 9);
  assert.ok(data.harbours.length >= 8);
  assert.ok(Object.keys(data.ships).length >= 3);
});

test('harbour tax is read as a fraction', () => {
  assert.strictEqual(normaliseHarbour({ id: 'h', tax: '5' }).tax, 0.05);
  assert.strictEqual(normaliseHarbour({ id: 'h', tax: '12%' }).tax, 0.12);
});

test('a bad row is reported, not thrown', () => {
  const problems = [];
  normaliseOffer({ id: 'o', good: 'rope', percent: '150' }, problems);
  assert.strictEqual(problems.length, 1);
});

test('ship wages keep their cents', () => {
  assert.strictEqual(normaliseShips({ s: { wage: '1.25', crew: '8' } }).s.wage, 1.25);
});

test('converters fall back on rubbish', () => {
  assert.strictEqual(convert.int('x', 7), 7);
  assert.strictEqual(convert.money('x', 0), 0);
  assert.deepStrictEqual(convert.range([3, 1], null), null);
});
