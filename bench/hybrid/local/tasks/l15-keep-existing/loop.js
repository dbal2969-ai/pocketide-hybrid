const state = { ticks: 0, frames: 0 };
function tick() {
  state.ticks++;
}
module.exports = { state, tick };
