(function () {
  'use strict';
  const A = window.App;
  const lines = [{ text: 'Design', qty: 2, unitPrice: 150 }, { text: 'Hosting', qty: 1, unitPrice: 60, discount: 0.25 }];
  document.getElementById('inv').textContent = A.invoiceView.rows(lines).join('\n');
})();
