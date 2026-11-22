(function () {
  'use strict';
  const A = window.App;
  let books = [];
  try { books = JSON.parse(localStorage.getItem('shelf.books') || '[]'); } catch (e) { books = []; }
  const q = document.getElementById('q');
  const by = document.getElementById('by');
  function draw() { A.list.render(document, A.catalogue.sorted(A.catalogue.search(books, q.value), by.value)); }
  q.addEventListener('input', draw);
  by.addEventListener('change', draw);
  document.getElementById('file').addEventListener('change', async e => {
    const f = e.target.files[0];
    if (!f) return;
    try {
      const n = A.catalogue.addAll(books, A.importBooks.importBooks(await f.text()));
      localStorage.setItem('shelf.books', JSON.stringify(books));
      document.getElementById('status').textContent = 'Added ' + n + ' books.';
    } catch (err) {
      document.getElementById('status').textContent = 'Could not import: ' + err.message;
    }
    draw();
  });
  draw();
})();
