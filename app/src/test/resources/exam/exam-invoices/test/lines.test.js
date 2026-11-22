const test = require('node:test');
const assert = require('assert');
const { gross } = require('../lib/lines.js');

test('a full-price line gets VAT once', () => {
  assert.strictEqual(gross({ qty: 2, unitPrice: 150 }), 360);
});
