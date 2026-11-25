const { slugify, titleCase } = require('./strings.js');
const { clamp, sum, avg } = require('./numbers.js');
function unique(xs) {
  return [...new Set(xs)];
}
module.exports = { slugify, titleCase, clamp, sum, avg, unique };
