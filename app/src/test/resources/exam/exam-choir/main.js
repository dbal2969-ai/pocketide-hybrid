(function () {
  'use strict';
  const A = window.App;
  fetch('data/singers.json').then(r => r.json()).then(s => {
    const ul = document.getElementById('rota');
    A.rota.next(s, 0, 8).forEach((name, i) => { const li = document.createElement('li'); li.textContent = 'Practice ' + (i + 1) + ': ' + name; ul.append(li); });
  });
})();
