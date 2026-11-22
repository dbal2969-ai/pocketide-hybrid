(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { quiz: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // Questions to play: [{ q, answers: [...], correct: index of the right answer }]. In data/quiz.json the right answer is first.
  // rng() gives a number in [0, 1) (Math.random in the browser; tests pass their own).
  function prepare(questions, rng) {
    return questions.map(x => ({ q: x.q, answers: x.answers.slice(), correct: 0 }));
  }

  return { prepare };
});
