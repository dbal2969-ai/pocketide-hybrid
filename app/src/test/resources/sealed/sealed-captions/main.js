(function () {
  'use strict';
  const A = window.App;
  fetch('data/album.json').then(r => r.json()).then(album => {
    const grid = document.getElementById('grid');
    A.gallery.items(album).forEach((it, i) => {
      const fig = document.createElement('figure');
      const img = document.createElement('img'); img.src = it.src; img.alt = '';
      const cap = document.createElement('figcaption'); cap.textContent = it.text;
      fig.append(img, cap);
      fig.addEventListener('click', () => { const v = A.lightbox.view(album, i); document.getElementById('big').src = v.src; document.getElementById('bigcap').textContent = v.text + ' (' + v.position + ')'; });
      grid.append(fig);
    });
    document.getElementById('export').addEventListener('click', () => { document.getElementById('html').textContent = A.exportPage.toHtml(album); });
  });
})();
