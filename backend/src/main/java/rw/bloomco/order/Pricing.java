package rw.bloomco.order;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import rw.bloomco.common.Json;
import rw.bloomco.settings.SettingsService;

/**
 * Pure pricing rules (no I/O) - shared by cart, checkout quote and order creation,
 * and unit-tested in PricingTest.
 *
 *  subtotal  = Σ unitPrice × qty         (unitPrice already includes the product's own discount)
 *  discount  = promo % of subtotal        (only if the promo is valid and its minimum order is reached)
 *  delivery  = 0 when subtotal - discount ≥ free-delivery threshold, else the flat fee
 *  total     = subtotal - discount + delivery + gift options
 */
public final class Pricing {

    private Pricing() {}

    public record Line(long price, int discountPercent, int quantity) {}

    public record Promo(String code, String title, int discountPercent, long minOrder, boolean active,
            LocalDate startsAt, LocalDate endsAt) {}

    public record Totals(long subtotal, long savings, long discount, long deliveryFee, long giftTotal, long total, int itemCount) {
        public Map<String, Object> toJson() {
            return Json.obj("subtotal", subtotal, "savings", savings, "discount", discount, "deliveryFee", deliveryFee,
                    "giftTotal", giftTotal, "total", total, "itemCount", itemCount);
        }
    }

    public static long unitPrice(long price, int discountPercent) {
        return Math.round(price * (1 - discountPercent / 100.0));
    }

    public static boolean promoApplies(Promo promo, long subtotal, LocalDate today) {
        if (promo == null || !promo.active()) return false;
        if (promo.startsAt() != null && promo.startsAt().isAfter(today)) return false;
        if (promo.endsAt() != null && promo.endsAt().isBefore(today)) return false;
        return subtotal >= promo.minOrder();
    }

    public static Totals compute(List<Line> lines, SettingsService.Delivery settings, long giftTotal, Promo promo, LocalDate today) {
        long subtotal = 0;
        long savings = 0;
        int items = 0;
        for (Line l : lines) {
            long unit = unitPrice(l.price(), l.discountPercent());
            subtotal += unit * l.quantity();
            savings += (l.price() - unit) * l.quantity();
            items += l.quantity();
        }
        long discount = promoApplies(promo, subtotal, today) ? Math.round(subtotal * promo.discountPercent() / 100.0) : 0;
        long afterDiscount = subtotal - discount;
        long delivery = lines.isEmpty() || afterDiscount >= settings.freeDeliveryThreshold() ? 0 : settings.deliveryFee();
        return new Totals(subtotal, savings, discount, delivery, giftTotal, afterDiscount + delivery + giftTotal, items);
    }

    public static Totals compute(List<Line> lines, SettingsService.Delivery settings) {
        return compute(lines, settings, 0, null, LocalDate.now());
    }
}
