const { MAX_HP } = require('./config.js');
function bar(hp) { return hp + '/' + MAX_HP; }
module.exports = { bar };
