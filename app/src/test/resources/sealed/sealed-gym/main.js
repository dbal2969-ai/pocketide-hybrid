(function () {
  'use strict';
  const A = window.App;
  fetch('data/sessions.json').then(r => r.json()).then(sessions => {
    A.progress.render(document, sessions);
    const log = document.getElementById('log');
    for (const s of sessions.slice(-5).reverse()) {
      const art = document.createElement('article');
      for (const l of A.sessionView.lines(s)) { const p = document.createElement('p'); p.textContent = l; art.append(p); }
      log.append(art);
    }
  });
})();
