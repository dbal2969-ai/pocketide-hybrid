(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { stopList: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // the stops along the route; tapping one shows its board
  function render(doc, timetable, current, onPick) {
    const ul = doc.getElementById('stops');
    ul.innerHTML = '';
    for (const s of timetable.stops) {
      const li = doc.createElement('li');
      const b = doc.createElement('button');
      b.textContent = s.name;
      if (s.id === current) b.setAttribute('aria-current', 'true');
      b.addEventListener('click', () => onPick(s.id));
      li.append(b);
      ul.append(li);
    }
  }

  return { render };
});
