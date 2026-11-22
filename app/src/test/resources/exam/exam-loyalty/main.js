(function () {
  'use strict';
  const A = window.App;
  const card = A.card.create();
  const out = document.getElementById('card');
  function draw() { out.textContent = A.card.line(card); }
  document.getElementById('stamp').addEventListener('click', () => { A.card.stamp(card); draw(); });
  draw();
})();
