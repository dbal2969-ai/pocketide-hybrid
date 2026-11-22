const test = require('node:test');
const assert = require('assert');
const { order } = require('../lib/round.js');

test('a round is as long as the deck', () => {
  assert.strictEqual(order([1, 2, 3]).length, 3);
});
