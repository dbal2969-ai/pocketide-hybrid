let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const fs = require('fs');
const html = fs.readFileSync(__dirname + '/index.html', 'utf8');
const css = fs.readFileSync(__dirname + '/style.css', 'utf8');
need(/<button[^>]*id="start"[^>]*class="[^"]*\bbig\b|<button[^>]*class="[^"]*\bbig\b[^>]*id="start"/.test(html), 'the Start button needs class big');
need(/\.big\s*\{[^}]*font-size:\s*24px/.test(css), 'style.css needs .big { font-size: 24px }');
if (bad) process.exit(1);
console.log('ok');
