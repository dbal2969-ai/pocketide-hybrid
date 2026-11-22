(function () {
  'use strict';
  const A = window.App;
  fetch('data/quiz.json').then(r => r.json()).then(data => {
    const played = A.quiz.prepare(data, Math.random);
    const given = [];
    let i = 0, left = 0, timer = null;
    const box = document.getElementById('q');
    function next() {
      clearInterval(timer);
      if (i >= played.length) { box.textContent = A.score.result(A.score.tally(played, given)); return; }
      const x = played[i];
      box.innerHTML = '';
      const h = document.createElement('h2'); h.textContent = x.q; box.append(h);
      x.answers.forEach((a, k) => { const b = document.createElement('button'); b.textContent = a; b.addEventListener('click', () => { given[i] = k; i++; next(); }); box.append(b); });
      left = A.play.start();
      document.getElementById('time').textContent = A.play.countdown(left);
      timer = setInterval(() => { left--; document.getElementById('time').textContent = A.play.countdown(left); if (left <= 0) { given[i] = null; i++; next(); } }, 1000);
    }
    next();
  });
})();
