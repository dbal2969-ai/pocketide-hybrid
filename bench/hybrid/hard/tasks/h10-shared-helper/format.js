// Prices for display. symbol defaults to '$'.
function formatPrice(n, symbol) {
  return (symbol || '$') + n;
}
module.exports = { formatPrice };
