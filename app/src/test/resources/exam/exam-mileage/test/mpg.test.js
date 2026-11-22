const test = require('node:test');
const assert = require('assert');
const { of } = require('../lib/mpg.js');

test('one fill-up', () => { assert.strictEqual(of({ miles: 300, gallons: 10 }), 30); });
