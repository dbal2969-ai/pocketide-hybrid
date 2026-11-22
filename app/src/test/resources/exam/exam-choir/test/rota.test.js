const test = require('node:test');
const assert = require('assert');
const { whoFor } = require('../lib/rota.js');

test('the first singer starts', () => {
  assert.strictEqual(whoFor(['A', 'B', 'C'], 0), 'A');
  assert.strictEqual(whoFor([], 3), null);
});
