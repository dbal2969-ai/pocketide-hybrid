const { fetchItem } = require('./api.js');

// Loads every id. Should return one entry per id, in the same order as ids.
async function loadAll(ids) {
  const results = [];
  ids.forEach(async (id, i) => {
    const item = await fetchItem(id, i);
    results.push(item);
  });
  return results;
}

module.exports = { loadAll };
