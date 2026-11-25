class Counter {
  constructor() { this.count = 0; }
  inc() { this.count++; return this.count; }
  reset() { this.count = 0; }
}
module.exports = { Counter };
