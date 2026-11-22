(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { play: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { SECONDS_PER_QUESTION } = require('../lib/config.js');

  // the countdown text for a question
  function countdown(left) {
    return left + 's';
  }

  function start() {
    return SECONDS_PER_QUESTION;
  }

  return { countdown, start };
});
