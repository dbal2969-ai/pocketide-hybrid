(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { time: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  function pad(n) {
    return (n < 10 ? '0' : '') + n;
  }

  // minutes after midnight of the service day -> "HH:MM" on the clock
  function hhmm(mins) {
    return pad(Math.floor(mins / 60)) + ':' + pad(mins % 60);
  }

  // the service day's minutes now; before 3am still counts as the day before
  function serviceNow(date) {
    const m = date.getHours() * 60 + date.getMinutes();
    return m < 180 ? m + 1440 : m;
  }

  return { pad, hhmm, serviceNow };
});
