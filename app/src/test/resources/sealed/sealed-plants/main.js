(function () {
  'use strict';
  const A = window.App;
  Promise.all(['data/plants.json', 'data/groups.json'].map(p => fetch(p).then(r => r.json()))).then(([plants, groups]) => {
    const state = A.state.create(plants);
    const today = new Date().toISOString().slice(0, 10);
    function draw() {
      const week = document.getElementById('week');
      week.innerHTML = '';
      for (const x of A.schedule.thisWeek(state.plants, groups, today)) {
        const li = document.createElement('li');
        const b = document.createElement('button');
        b.textContent = 'Watered';
        b.addEventListener('click', () => { A.state.markWatered(state, x.plant.id, today); draw(); });
        li.append(x.plant.name + ' \u00b7 ' + A.dates.show(x.next) + ' ', b);
        week.append(li);
      }
      const hist = document.getElementById('history');
      hist.innerHTML = '';
      for (const r of A.historyView.rows(state)) { const li = document.createElement('li'); li.textContent = r; hist.append(li); }
    }
    draw();
  });
})();
