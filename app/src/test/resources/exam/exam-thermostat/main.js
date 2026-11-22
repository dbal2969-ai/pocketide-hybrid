(function () {
  'use strict';
  const A = window.App;
  let s = A.settings.load(window.localStorage);
  const pick = document.getElementById('unit');
  pick.value = s.unit;
  function draw() { document.getElementById('temp').textContent = A.units.show(19.5, s.unit); }
  pick.addEventListener('change', () => { s = Object.assign({}, s, { unit: pick.value }); A.settings.save(window.localStorage, s); draw(); });
  draw();
})();
