// Hidden check for l19-html-and-js: inputs the model never saw (run by analyze.py, not shown in the app).
let bad = 0;
function need(c, msg) { if (!c) { console.error('hidden check: ' + msg); bad++; } }
const fs = require('fs');
const html = fs.readFileSync(__dirname + '/index.html', 'utf8');
need(/<script[^>]*src="app\.js"/.test(html), 'index.html no longer loads app.js');
need(/<button[^>]*id="add"/.test(html), 'the Add button is gone');
need((html.match(/<\/body>/g) || []).length === 1, '</body> is not written once');
const a = require('./app.js');
a.add(); a.add(); a.add(); a.reset();
need(a.get() === 0, 'reset left ' + a.get());
need(a.add() === 1, 'add after reset gave a wrong count');
if (bad) process.exit(1);
console.log('ok');
