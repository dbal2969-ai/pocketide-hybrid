(function () {
  'use strict';
  const A = window.App;
  fetch('data/deck.json').then(r => r.json()).then(deck => {
    let cards = A.round.order(deck), i = 0, flipped = false;
    const box = document.getElementById('card');
    function show() { const c = cards[i]; box.textContent = c ? (flipped ? c.a : c.q) : 'Round done'; }
    box.addEventListener('click', () => { if (flipped) { i++; flipped = false; } else flipped = true; show(); });
    show();
  });
})();
