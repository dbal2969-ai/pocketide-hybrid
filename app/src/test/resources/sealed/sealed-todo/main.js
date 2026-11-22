(function () {
  'use strict';
  const A = window.App;
  fetch('data/todos.json').then(r => r.json()).then(todos => {
    let tag = '';
    function draw() {
      const ul = document.getElementById('list');
      ul.innerHTML = '';
      const shown = A.todos.byDue(tag ? A.tags.withTag(todos, tag) : todos);
      for (const t of shown) {
        const li = document.createElement('li');
        li.textContent = A.listView.line(t);
        li.addEventListener('click', () => { A.todos.toggle(todos, t.id); draw(); });
        ul.append(li);
      }
      const sel = document.getElementById('tag');
      sel.innerHTML = '<option value="">All</option>';
      for (const g of A.tags.allTags(todos)) { const o = document.createElement('option'); o.value = g; o.textContent = g; if (g === tag) o.selected = true; sel.append(o); }
    }
    document.getElementById('tag').addEventListener('change', e => { tag = e.target.value; draw(); });
    document.getElementById('export').addEventListener('click', () => { document.getElementById('out').textContent = A.exportText.toText(todos); });
    draw();
  });
})();
