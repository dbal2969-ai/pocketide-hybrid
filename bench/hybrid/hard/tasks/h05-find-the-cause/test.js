const { line } = require('./src/report.js');
const { dueLine } = require('./src/invoice.js');
let bad = 0;
const a = line({ at: new Date(Date.UTC(2026, 0, 5)), what: 'tea', amount: 3.5 });
if (a !== '2026-01-05 tea 3.50') { console.error('report line: ' + a); bad++; }
const b = dueLine(new Date(Date.UTC(2026, 10, 25)), 14);
if (b !== 'Due 2026-12-09') { console.error('invoice: ' + b); bad++; }
if (bad) process.exit(1);
console.log('ok');
