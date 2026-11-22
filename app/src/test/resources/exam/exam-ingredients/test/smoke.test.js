const test = require('node:test');
const assert = require('assert');

test('the page loads its modules', () => {
  assert.ok(require('../lib/readIngredients.js'));
});
