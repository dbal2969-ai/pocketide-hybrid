const { slugify, avg, unique } = require('./utils.js');
function summary(posts) {
  return {
    slugs: posts.map(p => slugify(p.title)),
    meanLikes: avg(posts.map(p => p.likes)),
    tags: unique(posts.flatMap(p => p.tags)),
  };
}
module.exports = { summary };
