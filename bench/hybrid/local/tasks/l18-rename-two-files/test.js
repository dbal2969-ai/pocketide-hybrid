let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const s = require('./score.js');
const { banner } = require('./ui.js');
need(typeof s.readScore === 'function' && !s.getScore, 'score.js should export readScore, not getScore');
need(banner({ points: 2 }) === 'Score: 20', 'banner gave ' + banner({ points: 2 }));
if (bad) process.exit(1);
console.log('ok');
