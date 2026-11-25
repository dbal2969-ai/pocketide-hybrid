const { getScore } = require('./score.js');
function banner(s) {
  return 'Score: ' + getScore(s);
}
module.exports = { banner };
