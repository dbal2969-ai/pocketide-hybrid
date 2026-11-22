const test = require('node:test');
const assert = require('assert');
const { byCategory } = require('../lib/budget.js');
const rules = [{ word: 'cafe', category: 'Eating out' }];

test('lines add up by category', () => {
  assert.deepStrictEqual(byCategory([{ description: 'Corner Cafe', amount: -3.2 }, { description: 'CAFE NERO', amount: -2.1 },
    { description: 'Gift', amount: 10 }], rules), { 'Eating out': -5.3, Other: 10 });
});
