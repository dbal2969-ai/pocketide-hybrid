const fs = require('fs');
let bad = 0;
function need(cond, msg) { if (!cond) { console.error(msg); bad++; } }
let strings = {}, numbers = {};
try { strings = require('./strings.js'); } catch (e) { need(false, 'strings.js is missing'); }
try { numbers = require('./numbers.js'); } catch (e) { need(false, 'numbers.js is missing'); }
const utils = require('./utils.js');
need(typeof strings.slugify === 'function' && typeof strings.titleCase === 'function', 'strings.js must export slugify and titleCase');
need(typeof numbers.clamp === 'function' && typeof numbers.sum === 'function' && typeof numbers.avg === 'function', 'numbers.js must export clamp, sum and avg');
for (const f of ['slugify', 'titleCase', 'clamp', 'sum', 'avg', 'unique']) need(typeof utils[f] === 'function', 'utils.js must still export ' + f);
const src = fs.readFileSync(__dirname + '/utils.js', 'utf8');
need(!/function\s+(slugify|titleCase|clamp|sum|avg)\b/.test(src), 'utils.js should re-export the moved functions, not define them again');
if (strings.slugify) need(strings.slugify('  Hello, World! ') === 'hello-world', 'slugify changed');
if (strings.titleCase) need(strings.titleCase('the DAY of days') === 'The Day Of Days', 'titleCase changed');
if (numbers.clamp) need(numbers.clamp(12, 0, 10) === 10, 'clamp changed');
const { summary } = require('./app.js');
const s = summary([{ title: 'A post', likes: 2, tags: ['x'] }, { title: 'B!', likes: 4, tags: ['x', 'y'] }]);
need(JSON.stringify(s) === '{"slugs":["a-post","b"],"meanLikes":3,"tags":["x","y"]}', 'app.js summary changed: ' + JSON.stringify(s));
if (bad) process.exit(1);
console.log('ok');
