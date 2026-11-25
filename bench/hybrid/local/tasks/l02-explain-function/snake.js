class Snake {
  constructor() { this.body = [{ x: 1, y: 1 }]; }
  step() {
    const head = { x: this.body[0].x + 1, y: this.body[0].y };
    this.body.unshift(head);
    return this.body.length;
  }
}
module.exports = { Snake };
