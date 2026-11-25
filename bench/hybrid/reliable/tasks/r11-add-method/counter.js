class Counter {
  constructor() { this.count = 0; }
  inc() { this.count++; return this.count; }
}
module.exports = { Counter };
