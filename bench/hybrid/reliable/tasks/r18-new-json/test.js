let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
let l = [];
try { l = JSON.parse(require('fs').readFileSync(__dirname + '/levels.json', 'utf8')); } catch (e) { need(false, 'levels.json missing or not JSON: ' + e.message); }
need(Array.isArray(l) && l.length === 3, 'need three levels');
need(l.map(x => x.enemies).join() === '2,4,6', 'enemies are ' + l.map(x => x.enemies).join());
if (bad) process.exit(1);
console.log('ok');
