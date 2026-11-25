const { fetchItem } = require('./api.js');

// Loads every id. Should return one entry per id, in the same order as ids.
async function loadAll(ids) {
  return Promise.all(ids.map((id, i) => fetchItem(id, i).catch(e => ({ id, error: e.message }))));
}

module.exports = { loadAll };
