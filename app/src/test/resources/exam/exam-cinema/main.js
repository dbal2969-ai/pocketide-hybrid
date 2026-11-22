(function () {
  'use strict';
  const A = window.App;
  const hall = A.seats.create();
  function draw() { document.getElementById('hall').textContent = A.hallView.rows(hall).join('\n'); }
  document.getElementById('book').addEventListener('click', () => {
    const v = document.getElementById('seat').value.trim().toUpperCase();
    const ok = A.seats.book(hall, v[0], Number(v.slice(1)));
    document.getElementById('msg').textContent = ok ? 'Booked ' + v : 'Could not book ' + v;
    draw();
  });
  draw();
})();
