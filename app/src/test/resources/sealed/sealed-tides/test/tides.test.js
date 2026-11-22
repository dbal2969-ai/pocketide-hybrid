const test = require('node:test');
const assert = require('assert');
const { upcoming, highest } = require('../lib/tides.js');
const list = [{ day: 'Mon', type: 'High', time: '06:05', height: 4.2 }, { day: 'Mon', type: 'Low', time: '12:20', height: 0.9 },
  { day: 'Mon', type: 'High', time: '18:31', height: 4.4 }];

test('upcoming starts at the next tide', () => {
  assert.strictEqual(upcoming(list, 'Mon', '07:00')[0].time, '12:20');
});

test('the highest high tide', () => {
  assert.strictEqual(highest(list).time, '18:31');
});
