const test = require('node:test');
const assert = require('assert');
const { easyOnly } = require('../lib/filter.js');

test('easy walks only', () => {
  assert.deepStrictEqual(easyOnly([{ grade: 'easy' }, { grade: 'hard' }]), [{ grade: 'easy' }]);
});
