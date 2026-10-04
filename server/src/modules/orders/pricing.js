/**
 * Pure pricing rules (no I/O) - shared by cart, checkout quote and order creation,
 * and unit-tested in tests/pricing.test.js.
 *
 *  subtotal  = Σ unitPrice × qty          (unitPrice already includes the product's own discount)
 *  discount  = promo % of subtotal         (only if the promo is valid and min order is reached)
 *  delivery  = 0 when subtotal - discount ≥ free-delivery threshold, else the flat fee
 *  total     = subtotal - discount + delivery + gift options
 */
export const unitPrice = (price, discountPercent = 0) => Math.round(Number(price) * (1 - discountPercent / 100));

export function promoApplies(promo, subtotal, today = new Date()) {
  if (!promo || !promo.active) return false;
  const day = today.toISOString().slice(0, 10);
  if (promo.starts_at && String(promo.starts_at).slice(0, 10) > day) return false;
  if (promo.ends_at && String(promo.ends_at).slice(0, 10) < day) return false;
  return subtotal >= Number(promo.min_order || 0);
}

export function computeTotals({ lines, settings, giftTotal = 0, promo = null, today }) {
  const subtotal = lines.reduce((sum, l) => sum + unitPrice(l.price, l.discountPercent) * l.quantity, 0);
  const savings = lines.reduce((sum, l) => sum + (Number(l.price) - unitPrice(l.price, l.discountPercent)) * l.quantity, 0);
  const discount = promoApplies(promo, subtotal, today) ? Math.round((subtotal * promo.discount_percent) / 100) : 0;
  const afterDiscount = subtotal - discount;
  const deliveryFee =
    lines.length === 0 || afterDiscount >= Number(settings.freeDeliveryThreshold) ? 0 : Number(settings.deliveryFee);
  return {
    subtotal,
    savings,
    discount,
    deliveryFee,
    giftTotal,
    total: afterDiscount + deliveryFee + giftTotal,
    itemCount: lines.reduce((n, l) => n + l.quantity, 0),
  };
}
