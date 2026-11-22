(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { score: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // given answers: one per question, the chosen index, or null when the time ran out with nothing chosen
  function tally(played, given) {
    let right = 0;
    played.forEach((x, i) => { if (given[i] === x.correct) right++; });
    return { right, of: played.length };
  }

  // the line shown at the end
  function result(t) {
    return 'You scored ' + t.right + ' out of ' + t.of;
  }

  return { tally, result };
});
