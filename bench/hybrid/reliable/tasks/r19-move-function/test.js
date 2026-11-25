let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
let m = {};
try { m = require('./money.js'); } catch (e) { need(false, 'money.js is missing'); }
need(m.formatMoney && m.formatMoney(3) === '$3.00', 'money.js should export formatMoney');
const { receipt } = require('./app.js');
need(receipt(2.5) === 'Total: $2.50', 'receipt gave ' + receipt(2.5));
need(!/function formatMoney/.test(require('fs').readFileSync(__dirname + '/app.js', 'utf8')), 'app.js still defines formatMoney');
if (bad) process.exit(1);
console.log('ok');
