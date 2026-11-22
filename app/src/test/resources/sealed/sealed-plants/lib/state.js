(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { state: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // The planner's state: { plants, history: [{ plantId, date }] }. The history screen lists state.history.
  function create(plants) {
    return { plants: plants.map(p => Object.assign({}, p)), history: [] };
  }

  // ticking a plant as watered on `date`
  function markWatered(state, plantId, date) {
    const p = state.plants.find(x => x.id === plantId);
    if (!p) throw new Error('no plant ' + plantId);
    p.lastWatered = date;
    return state;
  }

  return { create, markWatered };
});
