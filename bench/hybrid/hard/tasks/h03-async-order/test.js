const { loadAll } = require('./loader.js');
(async () => {
  let bad = 0;
  const r = await loadAll(['a', 'b', 'c']);
  if (r.length !== 3) { console.error('got ' + r.length + ' results, want 3'); bad++; }
  else if (r.map(x => x.id).join() !== 'a,b,c') { console.error('order ' + r.map(x => x.id).join() + ', want a,b,c'); bad++; }
  let r2;
  try { r2 = await loadAll(['a', 'bad', 'c']); } catch (e) { console.error('a failed id threw: ' + e.message); bad++; }
  if (r2) {
    if (r2.length !== 3 || r2[1].id !== 'bad' || r2[1].error !== 'not found: bad' || r2[2].name !== 'Item c') {
      console.error('failed id should give { id, error } in its place: ' + JSON.stringify(r2)); bad++;
    }
  }
  if (bad) process.exit(1);
  console.log('ok');
})();
