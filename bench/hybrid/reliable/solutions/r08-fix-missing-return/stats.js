function average(nums) {
  const sum = nums.reduce((a, b) => a + b, 0);
  return sum / nums.length;
}
module.exports = { average };
