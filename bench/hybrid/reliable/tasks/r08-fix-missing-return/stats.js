function average(nums) {
  const sum = nums.reduce((a, b) => a + b, 0);
  sum / nums.length;
}
module.exports = { average };
