(function () {
  'use strict';
  const A = window.App;
  fetch('data/fills.json').then(r => r.json()).then(f => { document.getElementById('avg').textContent = 'Average: ' + A.mpg.average(f).toFixed(1) + ' mpg'; });
})();
