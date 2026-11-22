(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { progress: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { weeklyTotals } = require('../lib/weeks.js');

  function rows(sessions) {
    return weeklyTotals(sessions).map(w => 'Week of ' + w.week + ': ' + w.volume.toLocaleString('en-GB') + ' kg (' + w.sessions + ' sessions)');
  }

  function render(doc, sessions) {
    const ul = doc.getElementById('progress');
    ul.innerHTML = '';
    for (const r of rows(sessions)) { const li = doc.createElement('li'); li.textContent = r; ul.append(li); }
  }

  return { rows, render };
});
