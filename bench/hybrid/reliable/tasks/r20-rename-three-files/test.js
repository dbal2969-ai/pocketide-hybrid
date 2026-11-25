let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const fs = require('fs');
const all = ['config.js', 'player.js', 'hud.js'].map(f => fs.readFileSync(__dirname + '/' + f, 'utf8')).join('\n');
need(!/MAX_HP\b/.test(all), 'MAX_HP is still used');
need(require('./config.js').MAX_HEALTH === 10, 'config.js should export MAX_HEALTH');
need(require('./hud.js').bar(4) === '4/10', 'bar gave ' + require('./hud.js').bar(4));
if (bad) process.exit(1);
console.log('ok');
