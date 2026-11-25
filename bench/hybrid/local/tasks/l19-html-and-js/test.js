let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const fs = require('fs');
const html = fs.readFileSync(__dirname + '/index.html', 'utf8');
need(/<button[^>]*id="reset"/.test(html), 'index.html needs a button with id="reset"');
const a = require('./app.js');
need(typeof a.reset === 'function', 'app.js should export reset');
if (a.reset) { a.add(); a.add(); a.reset(); need(a.get() === 0, 'reset left count at ' + a.get()); }
if (bad) process.exit(1);
console.log('ok');
