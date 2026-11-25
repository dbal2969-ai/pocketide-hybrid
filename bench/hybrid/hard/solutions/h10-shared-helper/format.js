// Prices for display. symbol defaults to '$'.
function formatPrice(n, symbol) {
  return (symbol || '$') + n.toFixed(2);
}
module.exports = { formatPrice };
