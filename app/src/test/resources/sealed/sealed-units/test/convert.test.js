const test = require('node:test');
const assert = require('assert');
const { convert, celsiusTo, toCelsius } = require('../lib/convert.js');

test('factors convert through the base unit', () => {
  assert.strictEqual(convert(2, 'km', 'm', { m: 1, km: 1000 }), 2000);
});

test('temperatures', () => {
  assert.strictEqual(celsiusTo(100, 'F'), 212);
  assert.strictEqual(toCelsius(212, 'F'), 100);
});
