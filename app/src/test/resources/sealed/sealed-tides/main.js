(function () {
  'use strict';
  const A = window.App;
  fetch('data/tides.html').then(r => r.text()).then(html => {
    const list = A.readTides.readTides(html);
    A.table.render(document, list);
    const h = A.tides.highest(list);
    document.getElementById('highest').textContent = h ? 'Highest: ' + h.height.toFixed(1) + ' m on ' + h.day + ' at ' + h.time : '';
  });
})();
