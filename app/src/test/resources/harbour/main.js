// Starts the game in the browser: loads the data, builds the world, and wires the screens to the state.
(function () {
  'use strict';
  const H = window.Harbour;
  const SEED_KEY = 'harbour.seed';
  const SAVE_KEY = 'harbour.save';

  function storage() {
    try { return window.localStorage; } catch (e) { return null; }
  }

  function newSeed() {
    return (Date.now() ^ Math.floor(Math.random() * 0x7fffffff)) >>> 0;
  }

  async function start() {
    const data = await H.load.loadFetch('data/');
    if (data.problems.length) console.warn(data.problems.join('\n'));
    const store = storage();
    let seed = newSeed();
    const world = H.world.build(data, seed);
    const book = H.ledger.newLedger();
    let v = H.voyage.newVoyage(H.ship.choose(data.ships, 'brig'), 200, world.harbours[0]);
    const saved = store && store.getItem(SAVE_KEY);
    if (saved) {
      try { v = H.save.restore(H.save.fromSave(saved), world, H.voyage); } catch (e) { console.warn('save not loaded: ' + e.message); }
    }
    let c = H.cart.newCart();
    const doc = document;

    function table() { return H.prices.today(data.goods, v.at, seed, v.day); }

    function draw() {
      H.layout.applyLayout(doc, window.innerWidth);
      H.dom.text(doc, 'hud', H.hud.text(v));
      doc.getElementById('ship').setAttribute('style', H.shipIcon.style(window.innerWidth));
      H.harbourList.render(doc, world.harbours, id => { const h = H.world.harbourNamed(world, id); if (h) go(h); });
      H.marketView.render(doc, table(), id => { H.cart.add(c, H.world.goodNamed(world, id), 1); drawCart(); });
      H.sellView.render(doc, v, table(), id => sellOne(id));
      H.routesView.render(doc, v, world.harbours, id => go(H.world.harbourNamed(world, id)));
      const hold = H.cargo.rows(v, data.goods);
      H.dom.text(doc, 'hold', (hold.lines.join(', ') || 'empty') + ' · ' + hold.left);
      H.dom.lines(doc, 'ledger', H.ledgerView.lines(book));
      H.dom.lines(doc, 'log', v.log.slice(-6));
      const canvas = doc.getElementById('chart');
      canvas.width = canvas.clientWidth || 360;
      H.chart.draw(canvas, world.harbours, v.at);
      drawCart();
    }

    function drawCart() {
      H.dom.lines(doc, 'receipt', c.lines.length ? H.receipt.lines(c, data.offers) : ['Tap a good to add it']);
    }

    function go(h) {
      if (!h || h.id === v.at.id) return;
      const r = H.travel.travel(v, h, data.events, seed, book);
      if (!r.ok) v.log.push(r.why);
      persist();
      draw();
    }

    function sellOne(id) {
      const row = table().find(t => t.good.id === id);
      if (row) H.trade.sell(v, row.good, 1, row.sell, book);
      persist();
      draw();
    }

    function persist() {
      if (store) try { store.setItem(SAVE_KEY, H.save.toSave(v, seed, book)); } catch (e) { /* storage full or blocked */ }
    }

    doc.getElementById('buy').addEventListener('click', () => {
      const r = H.trade.buy(v, data.goods, c, data.offers, book);
      if (!r.ok) v.log.push(r.why); else c = H.cart.newCart();
      persist();
      draw();
    });
    doc.getElementById('clear').addEventListener('click', () => { c = H.cart.newCart(); drawCart(); });
    window.addEventListener('resize', draw);
    if (store) try { store.setItem(SEED_KEY, String(seed)); } catch (e) { /* ignore */ }
    draw();
  }

  start().catch(e => { document.getElementById('hud').textContent = 'Could not start: ' + e.message; });
})();
