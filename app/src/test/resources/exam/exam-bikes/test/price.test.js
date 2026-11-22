const test = require('node:test');
const assert = require('assert');
const { cost } = require('../lib/price.js');

test('whole hours and the cap', () => {
  assert.strictEqual(cost(60), 4);
  assert.strictEqual(cost(600), 20);
  assert.strictEqual(cost(0), 0);
});
