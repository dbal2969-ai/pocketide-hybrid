let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { area } = require('./shapes.js');
need(area({ width: 3, height: 4 }) === 12, 'area gave ' + area({ width: 3, height: 4 }));
if (bad) process.exit(1);
console.log('ok');
