const state = { ticks: 0, frames: 0 };
function tick() {
  state.ticks++;
  state.frames++;
}
module.exports = { state, tick };
