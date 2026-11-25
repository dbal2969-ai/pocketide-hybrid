const { readScore } = require('./score.js');
function banner(s) {
  return 'Score: ' + readScore(s);
}
module.exports = { banner };
