// Unit prices. Ten or more of one item get the bulk price.
const BULK_AT = 10;
function unitPrice(item, qty) {
  return qty >= BULK_AT ? item.bulkPrice : item.price;
}
module.exports = { unitPrice, BULK_AT };
