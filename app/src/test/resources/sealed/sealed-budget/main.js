(function () {
  'use strict';
  const A = window.App;
  fetch('data/rules.json').then(r => r.json()).then(rules => {
    const box = document.getElementById('paste');
    document.getElementById('add').addEventListener('click', () => {
      const lines = A.statement.readStatement(box.value);
      const ul = document.getElementById('totals');
      ul.innerHTML = '';
      for (const r of A.summary.rows(A.budget.byCategory(lines, rules))) { const li = document.createElement('li'); li.textContent = r; ul.append(li); }
      document.getElementById('status').textContent = lines.length + ' lines read.';
    });
  });
})();
