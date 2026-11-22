(function () {
  'use strict';
  const A = window.App;
  fetch('data/factors.json').then(r => r.json()).then(f => {
    const s = A.settings.DEFAULTS;
    const go = () => {
      const v = Number(document.getElementById('v').value);
      const [kind, from, to] = document.getElementById('pair').value.split(':');
      document.getElementById('out').textContent = kind === 'temperature' ? A.temperatureView.result(v, from, to, s) : A[kind + 'View'].result(v, from, to, f, s);
    };
    document.getElementById('v').addEventListener('input', go);
    document.getElementById('pair').addEventListener('change', go);
    go();
  });
})();
