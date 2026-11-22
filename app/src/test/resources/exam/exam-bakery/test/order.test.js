const test = require('node:test');
const assert = require('assert');
const { toLine } = require('../lib/order.js');

test('cake, size and day', () => {
  assert.match(toLine({ cake: 'Lemon', size: 'large', day: 'Friday' }), /^Lemon \(large\) for Friday/);
});
