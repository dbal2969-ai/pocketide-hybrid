const test = require('node:test');
const assert = require('assert');
const { loadSync } = require('../loader/load.js');
const crew = require('../state/crew.js');
const cart = require('../state/cart.js');
const world = require('../state/world.js');
const voyage = require('../state/voyage.js');
const trade = require('../state/trade.js');
const ledger = require('../state/ledger.js');
const routes = require('../state/routes.js');
const events = require('../state/events.js');
const save = require('../state/save.js');

const salt = { id: 'salt', name: 'Salt', category: 'Food', price: 12, weight: 2 };
const sloop = { id: 'sloop', name: 'Sloop', capacity: 40, crew: 8, wage: 1.5, speed: 9 };
const here = { id: 'a', name: 'A', region: 'R', tax: 0, x: 0, y: 0, makes: [] };
const there = { id: 'b', name: 'B', region: 'R', tax: 0, x: 300, y: 400, makes: [] };

test('the same seed builds the same world', () => {
  const data = loadSync();
  assert.deepStrictEqual(world.build(data, 7).harbours.map(h => h.id), world.build(data, 7).harbours.map(h => h.id));
});

test('crew wages are paid for the crew aboard', () => {
  const c = crew.newCrew(sloop);
  assert.strictEqual(crew.wagesFor(c, 2), 24);
  assert.strictEqual(crew.loseCrew(c, 3), 3);
  assert.strictEqual(c.count, 5);
});

test('a cart takes the best offer off once', () => {
  const offers = [{ id: 'o', good: 'salt', minQty: 10, percent: 10, label: 'Bulk salt' }];
  const c = cart.add(cart.newCart(), salt, 10);
  assert.strictEqual(cart.subtotal(c), 120);
  assert.strictEqual(cart.total(c, offers), 108);
  assert.strictEqual(cart.total(cart.add(cart.newCart(), salt, 2), offers), 24);
});

test('buying needs gold and space, and is written in the ledger', () => {
  const v = voyage.newVoyage(sloop, 100, here);
  const book = ledger.newLedger();
  assert.strictEqual(trade.buy(v, [salt], cart.add(cart.newCart(), salt, 30), [], book).ok, false);   // 60 t in a 40 t hold
  assert.strictEqual(trade.buy(v, [salt], cart.add(cart.newCart(), salt, 5), [], book).ok, true);
  assert.strictEqual(v.gold, 40);
  assert.strictEqual(v.hold.salt, 5);
  assert.strictEqual(trade.sell(v, salt, 2, 15, book).got, 30);
  assert.strictEqual(ledger.totals(book).profit, -30);
});

test('routes take longer for slower ships', () => {
  assert.strictEqual(routes.distance(here, there), 50);
  assert.strictEqual(routes.daysFor(here, there, sloop), 6);
  assert.strictEqual(routes.daysFor(here, there, Object.assign({}, sloop, { speed: 5 })), 10);
});

test('events are the same for the same seed', () => {
  const data = loadSync();
  assert.deepStrictEqual(events.roll(data.events, 30, 11), events.roll(data.events, 30, 11));
});

test('a storm takes crew overboard', () => {
  const v = voyage.newVoyage(sloop, 100, here);
  events.apply(v, [{ day: 2, type: 'storm', text: 'Storm', lost: 2 }]);
  assert.strictEqual(v.crew.count, 6);
  assert.strictEqual(v.crew.lost, 2);
});

test('a save comes back as it was', () => {
  const data = loadSync();
  const w = world.build(data, 3);
  const v = voyage.newVoyage(data.ships.brig, 150, w.harbours[2]);
  v.hold.rope = 4;
  const back = save.restore(save.fromSave(save.toSave(v, 3)), w, voyage);
  assert.strictEqual(back.at.id, v.at.id);
  assert.deepStrictEqual(back.hold, { rope: 4 });
});
