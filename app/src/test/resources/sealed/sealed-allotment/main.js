(function () {
  'use strict';
  const A = window.App;
  fetch('data/site.json').then(r => r.json()).then(site => {
    const map = document.getElementById('map');
    function put(l, cls) { const d = document.createElement('div'); d.className = cls; d.textContent = l.text; d.style.left = l.x + 'px'; d.style.top = l.y + 'px'; d.style.width = l.w + 'px'; map.append(d); }
    function draw() {
      const vw = document.documentElement.clientWidth;
      map.innerHTML = '';
      map.style.width = A.scale.mapWidth(vw) + 'px';
      A.plotLabels.labels(site.plots, vw).forEach(l => put(l, 'plot'));
      A.pathLabels.labels(site.paths, vw).forEach(l => put(l, 'path'));
      A.tapLabels.labels(site.taps, vw).forEach(l => put(l, 'tap'));
    }
    map.addEventListener('click', e => { const t = A.tooltip.place('Plot details', e.offsetX, e.offsetY, document.documentElement.clientWidth); put(t, 'tip'); });
    window.addEventListener('resize', draw);
    draw();
  });
})();
