const { MAX_HEALTH } = require('./config.js');
function bar(hp) { return hp + '/' + MAX_HEALTH; }
module.exports = { bar };
