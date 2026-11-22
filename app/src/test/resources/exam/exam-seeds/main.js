(function () {
  'use strict';
  const A = window.App;
  fetch('data/seeds.json').then(r => r.json()).then(seeds => {
    const nav = document.getElementById('tabs'), view = document.getElementById('view');
    const views = {
      tin: () => seeds.map(s => s.name).join(', '),
      notes: () => 'Keep the tin dry.',
      calendar: () => [1,2,3,4,5,6,7,8,9,10,11,12].map(m => m + ': ' + A.calendar.sowIn(seeds, m).join(', ')).join('\n'),
    };
    for (const t of A.tabs.TABS) { const b = document.createElement('button'); b.textContent = t.label; b.addEventListener('click', () => { view.textContent = views[t.id](); }); nav.append(b); }
    view.textContent = views.tin();
  });
})();
