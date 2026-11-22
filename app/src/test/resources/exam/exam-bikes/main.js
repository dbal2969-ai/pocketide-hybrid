(function () {
  'use strict';
  const A = window.App;
  const out = document.getElementById('cost');
  document.getElementById('mins').addEventListener('input', e => { out.textContent = A.money.show(A.price.cost(Number(e.target.value))); });
})();
