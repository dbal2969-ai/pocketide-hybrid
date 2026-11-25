let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
let c = {};
try { c = JSON.parse(require('fs').readFileSync(__dirname + '/config.json', 'utf8')); } catch (e) { need(false, 'config.json missing or not JSON: ' + e.message); }
need(c.speed === 150 && c.lives === 3, 'config.json is ' + JSON.stringify(c));
if (bad) process.exit(1);
console.log('ok');
