(function () {
  'use strict';
  const A = window.App;
  fetch('data/trails.json').then(r => r.json()).then(all => {
    const ul = document.getElementById('trails');
    const box = document.getElementById('easy');
    function draw() {
      ul.innerHTML = '';
      for (const t of (box && box.checked ? A.filter.easyOnly(all) : all)) { const li = document.createElement('li'); li.textContent = t.name + ' (' + t.km + ' km)'; ul.append(li); }
    }
    if (box) box.addEventListener('change', draw);
    draw();
  });
})();
