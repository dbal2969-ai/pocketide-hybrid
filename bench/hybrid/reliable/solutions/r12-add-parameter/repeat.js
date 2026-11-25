function repeat(word, times, sep = '') {
  return Array(times).fill(word).join(sep);
}
module.exports = { repeat };
