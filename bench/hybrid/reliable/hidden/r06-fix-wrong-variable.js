let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { area, perimeter } = require('./shapes.js');
need(area({ width: 2, height: 5 }) === 10, 'area');
need(perimeter({ width: 2, height: 5 }) === 14, 'perimeter changed');
if (bad) process.exit(1);
console.log('ok');
