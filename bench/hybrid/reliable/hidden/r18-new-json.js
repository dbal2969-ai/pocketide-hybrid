let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const l = JSON.parse(require('fs').readFileSync(__dirname + '/levels.json', 'utf8'));
need(l.every(x => typeof x.name === 'string' && x.name.length > 0), 'every level has a name');
if (bad) process.exit(1);
console.log('ok');
