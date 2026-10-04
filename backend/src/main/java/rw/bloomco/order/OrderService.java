package rw.bloomco.order;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import rw.bloomco.cart.CartService;
import rw.bloomco.common.ApiException;
import rw.bloomco.common.Db;
import rw.bloomco.common.Json;
import rw.bloomco.common.Paging;
import rw.bloomco.common.Row;
import rw.bloomco.common.Text;
import rw.bloomco.common.TtlCache;
import rw.bloomco.messaging.EventBus;
import rw.bloomco.payment.PaymentService;
import rw.bloomco.security.AuthUser;
import rw.bloomco.security.Permissions;
import rw.bloomco.settings.SettingsService;

/** Checkout, order queries and the order lifecycle state machine. */
@Service
public class OrderService {

    public static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    /** Allowed order lifecycle transitions (enforced server-side). */
    public static final Map<String, Set<String>> FLOW = Map.of(
            "pending", Set.of("confirmed", "cancelled"),
            "confirmed", Set.of("preparing", "cancelled"),
            "preparing", Set.of("ready", "cancelled"),
            "ready", Set.of("out_for_delivery"),
            "out_for_delivery", Set.of("delivered"),
            "delivered", Set.of(),
            "cancelled", Set.of());

    private final Db db;
    private final SettingsService settings;
    private final CartService cart;
    private final PaymentService payments;
    private final EventBus events;
    private final TtlCache cache;
    private final TransactionTemplate tx;

    public OrderService(Db db, SettingsService settings, CartService cart, PaymentService payments, EventBus events,
            TtlCache cache, TransactionTemplate tx) {
        this.db = db;
        this.settings = settings;
        this.cart = cart;
        this.payments = payments;
        this.events = events;
        this.cache = cache;
        this.tx = tx;
    }

    public static boolean canTransition(String from, String to) {
        return FLOW.getOrDefault(from, Set.of()).contains(to);
    }

    public static String formatOrderNumber(int id, int year) {
        return "FLW-" + year + "-" + String.format("%05d", id);
    }

    /** Throws 400 if the date is in the past, too far ahead, or today after the same-day cut-off. */
    public static void validateDeliverySlot(String date, int cutoffHour, LocalDateTime nowInKigali) {
        LocalDate day = LocalDate.parse(date);
        LocalDate today = nowInKigali.toLocalDate();
        if (day.isBefore(today)) throw ApiException.badRequest("Delivery date cannot be in the past");
        if (day.isAfter(today.plusDays(60))) throw ApiException.badRequest("Delivery can be scheduled at most 60 days ahead");
        if (day.equals(today) && nowInKigali.getHour() >= cutoffHour) {
            throw ApiException.badRequest("Same-day orders close at " + cutoffHour + ":00. Please choose tomorrow or later.");
        }
    }

    static String composeAddress(OrderController.Delivery d) {
        List<String> parts = new ArrayList<>();
        for (String s : new String[] {d.street(), d.sector(), d.district(), d.province()}) if (!Text.isBlank(s)) parts.add(s.trim());
        return String.join(", ", parts);
    }

    static Map<String, Object> toOrder(Row o) {
        String staffFirst = o.has("staff_first") ? o.str("staff_first") : null;
        return Json.obj(
                "id", o.integer("id"),
                "orderNumber", o.str("order_number"),
                "userId", o.integer("user_id"),
                "customerName", o.str("customer_name"),
                "email", o.str("email"),
                "phone", o.str("phone"),
                "subtotal", o.money("subtotal"),
                "discount", o.money("discount"),
                "giftTotal", o.money("gift_total"),
                "deliveryFee", o.money("delivery_fee"),
                "total", o.money("total_amount"),
                "address", Json.obj("province", o.str("province"), "district", o.str("district"), "sector", o.str("sector"),
                        "street", o.str("street"), "locationDescription", o.str("location_description"), "full", o.str("delivery_address")),
                "deliveryDate", o.date("delivery_date"),
                "deliveryTime", o.str("delivery_time"),
                "instructions", o.str("instructions"),
                "recipientName", o.str("recipient_name"),
                "messageType", o.str("message_type"),
                "giftMessage", o.str("gift_message"),
                "giftOptions", o.json("gift_options"),
                "paymentMethod", o.str("payment_method"),
                "paymentMethodLabel", PaymentService.METHOD_LABELS.get(o.str("payment_method")),
                "status", o.str("status"),
                "paymentStatus", o.str("payment_status"),
                "createdAt", o.ts("created_at"),
                "updatedAt", o.ts("updated_at"),
                "itemCount", o.has("item_count") ? o.lng("item_count") : null,
                "firstItem", o.has("first_item") ? o.str("first_item") : null,
                "firstImage", o.has("first_image") ? o.str("first_image") : null,
                "deliveryStatus", o.has("delivery_status") ? o.str("delivery_status") : null,
                "deliveryStaff", staffFirst != null ? staffFirst + " " + o.str("staff_last") : null);
    }

    // ---------------------------------------------------------------------------------- checkout

    private Pricing.Promo loadPromo(String code) {
        return db.one("SELECT * FROM promotions WHERE UPPER(code) = UPPER(?)", code)
                .map(r -> new Pricing.Promo(r.str("code"), r.str("title"), r.intOr0("discount_percent"), r.money("min_order"),
                        Boolean.TRUE.equals(r.bool("active")),
                        r.date("starts_at") == null ? null : LocalDate.parse(r.date("starts_at")),
                        r.date("ends_at") == null ? null : LocalDate.parse(r.date("ends_at"))))
                .orElse(null);
    }

    private List<Row> gifts(List<String> codes) {
        if (codes == null || codes.isEmpty()) return List.of();
        return db.rows("SELECT code, name, icon, price FROM gift_options WHERE active AND code = ANY(?)", db.textArray(codes));
    }

    private static Pricing.Line pricingLine(Row l) {
        return new Pricing.Line(l.money("price"), l.intOr0("discount_percent"), l.intOr0("quantity"));
    }

    private record Placed(Row order, List<Row> lines, Row payment) {}

    public Map<String, Object> create(int userId, OrderController.CheckoutRequest in) {
        var delivery = settings.delivery();
        var d = in.delivery();
        validateDeliverySlot(d.date(), delivery.sameDayCutoffHour(), LocalDateTime.now(KIGALI));
        if (!delivery.timeSlots().contains(d.time())) throw ApiException.badRequest("Choose one of the available delivery times");
        var payment = in.payment().toInput();
        String contactPhone = Text.cleanPhone(in.contact().phone());
        LocalDate today = LocalDate.now(KIGALI);

        Placed placed = tx.execute(status -> {
            // Lock the product rows so concurrent checkouts can't oversell stock.
            List<Row> lines = db.rows("""
                    SELECT ci.product_id, ci.quantity, p.name, p.price, p.discount_percent, p.stock, p.status, p.image_url
                    FROM cart_items ci
                    JOIN cart c ON c.id = ci.cart_id
                    JOIN products p ON p.id = ci.product_id
                    WHERE c.user_id = ? AND ci.saved_for_later = FALSE
                    ORDER BY p.id
                    FOR UPDATE OF p""", userId);
            if (lines.isEmpty()) throw ApiException.badRequest("Your cart is empty");
            for (Row l : lines) {
                if (!"active".equals(l.str("status"))) throw ApiException.badRequest(l.str("name") + " is no longer available - please remove it from your cart");
                if (l.intOr0("quantity") > l.intOr0("stock")) throw ApiException.badRequest("Only " + l.intOr0("stock") + " " + l.str("name") + " left in stock");
            }
            List<Row> gifts = gifts(in.giftOptions());
            long giftTotal = gifts.stream().mapToLong(g -> g.money("price")).sum();
            List<Pricing.Line> pricing = lines.stream().map(OrderService::pricingLine).toList();

            Pricing.Promo promo = null;
            Integer promoId = null;
            if (!Text.isBlank(in.promoCode())) {
                promo = loadPromo(in.promoCode().trim());
                long pre = Pricing.compute(pricing, delivery).subtotal();
                if (!Pricing.promoApplies(promo, pre, today)) throw ApiException.badRequest("This promo code is invalid, expired, or below its minimum order");
                promoId = db.one("SELECT id FROM promotions WHERE UPPER(code) = UPPER(?)", in.promoCode().trim()).map(r -> r.integer("id")).orElse(null);
            }
            var totals = Pricing.compute(pricing, delivery, giftTotal, promo, today);

            List<Map<String, Object>> giftJson = gifts.stream()
                    .map(g -> Json.obj("code", g.str("code"), "name", g.str("name"), "icon", g.str("icon"), "price", g.money("price")))
                    .toList();
            var msg = in.message();
            int orderId = db.insertId("""
                    INSERT INTO orders (user_id, subtotal, discount, gift_total, delivery_fee, total_amount, promotion_id,
                        customer_name, email, phone, province, district, sector, street, location_description, delivery_address,
                        delivery_date, delivery_time, instructions, recipient_name, message_type, gift_message, gift_options, payment_method)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::date,?,?,?,?,?,?::jsonb,?) RETURNING id""",
                    userId, totals.subtotal(), totals.discount(), giftTotal, totals.deliveryFee(), totals.total(), promoId,
                    in.contact().fullName().trim(), in.contact().email().trim().toLowerCase(), contactPhone,
                    d.province().trim(), d.district().trim(), d.sector().trim(), d.street().trim(), Text.blankToNull(d.locationDescription()),
                    composeAddress(d), d.date(), d.time(), Text.blankToNull(in.instructions()),
                    msg == null ? null : Text.blankToNull(msg.recipientName()), msg == null ? null : Text.blankToNull(msg.type()),
                    msg == null ? null : Text.blankToNull(msg.text()), toJson(giftJson), payment.method());
            db.update("UPDATE orders SET order_number = ? WHERE id = ?", formatOrderNumber(orderId, today.getYear()), orderId);

            for (Row l : lines) {
                db.update("INSERT INTO order_items (order_id, product_id, product_name, image_url, quantity, price) VALUES (?,?,?,?,?,?)",
                        orderId, l.integer("product_id"), l.str("name"), l.str("image_url"), l.intOr0("quantity"),
                        Pricing.unitPrice(l.money("price"), l.intOr0("discount_percent")));
                db.update("UPDATE products SET stock = stock - ?, sold_count = sold_count + ? WHERE id = ?",
                        l.intOr0("quantity"), l.intOr0("quantity"), l.integer("product_id"));
            }
            db.update("INSERT INTO order_status_history (order_id, status, changed_by, note) VALUES (?,'pending',?,'Order placed')", orderId, userId);
            db.update("INSERT INTO deliveries (order_id, delivery_address, delivery_date, delivery_time) VALUES (?,?,?::date,?)",
                    orderId, composeAddress(d), d.date(), d.time());

            // Charge the customer last: a decline throws and rolls the whole order back.
            var charge = PaymentService.authorize(payment);
            Row paymentRow = payments.record(orderId, payment.method(), totals.total(), charge);
            if ("paid".equals(charge.status())) db.update("UPDATE orders SET payment_status = 'paid' WHERE id = ?", orderId);
            cart.clearPurchased(userId);
            if (Boolean.TRUE.equals(in.saveAddress())) {
                db.update("""
                        INSERT INTO addresses (user_id, label, recipient_name, phone, province, district, sector, street, location_description, is_default)
                        VALUES (?,'Saved',?,?,?,?,?,?,?, NOT EXISTS (SELECT 1 FROM addresses WHERE user_id = ?))""",
                        userId, in.contact().fullName().trim(), contactPhone, d.province().trim(), d.district().trim(),
                        d.sector().trim(), d.street().trim(), Text.blankToNull(d.locationDescription()), userId);
            }
            Row order = db.one("SELECT * FROM orders WHERE id = ?", orderId).orElseThrow();
            return new Placed(order, lines, paymentRow);
        });

        cache.invalidate("products:");
        Row order = placed.order();
        events.publish("order.placed", Json.obj(
                "actorId", userId, "entity", "order", "entityId", order.integer("id"), "userId", userId, "orderId", order.integer("id"),
                "orderNumber", order.str("order_number"), "customerName", order.str("customer_name"), "email", order.str("email"),
                "phone", order.str("phone"), "total", order.money("total_amount"), "deliveryDate", order.date("delivery_date"),
                "deliveryTime", order.str("delivery_time"), "address", order.str("delivery_address"),
                "items", placed.lines().stream().map(l -> Json.obj("name", l.str("name"), "quantity", l.intOr0("quantity"))).toList()));
        Row p = placed.payment();
        if ("paid".equals(p.str("payment_status"))) {
            events.publish("payment.completed", Json.obj(
                    "actorId", userId, "entity", "payment", "entityId", p.integer("id"), "userId", userId, "email", order.str("email"),
                    "orderNumber", order.str("order_number"), "amount", p.money("amount"),
                    "method", PaymentService.METHOD_LABELS.get(p.str("payment_method")), "reference", p.str("transaction_reference")));
        }
        return Json.obj("order", toOrder(order), "payment", PaymentService.toPayment(p));
    }

    /** Checkout preview: totals for the current cart + gifts + promo, without writing anything. */
    public Map<String, Object> quote(int userId, List<String> giftCodes, String promoCode) {
        var delivery = settings.delivery();
        LocalDate today = LocalDate.now(KIGALI);
        List<Pricing.Line> lines = db.rows("""
                SELECT ci.quantity, p.price, p.discount_percent FROM cart_items ci JOIN cart c ON c.id = ci.cart_id
                JOIN products p ON p.id = ci.product_id
                WHERE c.user_id = ? AND ci.saved_for_later = FALSE AND p.status = 'active'""", userId)
                .stream().map(OrderService::pricingLine).toList();
        long giftTotal = gifts(giftCodes).stream().mapToLong(g -> g.money("price")).sum();
        Pricing.Promo promo = null;
        String promoMessage = null;
        if (!Text.isBlank(promoCode)) {
            promo = loadPromo(promoCode.trim());
            long pre = Pricing.compute(lines, delivery).subtotal();
            if (!Pricing.promoApplies(promo, pre, today)) {
                promoMessage = promo != null && promo.minOrder() > pre
                        ? "Spend " + Text.money(promo.minOrder()) + " or more to use this code"
                        : "This promo code is invalid or expired";
                promo = null;
            }
        }
        var totals = Pricing.compute(lines, delivery, giftTotal, promo, today);
        Map<String, Object> out = new LinkedHashMap<>(totals.toJson());
        out.put("promo", promo == null ? null : Json.obj("code", promo.code(), "title", promo.title(), "percent", promo.discountPercent()));
        out.put("promoMessage", promoMessage);
        return out;
    }

    // ---------------------------------------------------------------------------------- queries

    private static final String LIST_SELECT = """
            SELECT o.*,
              (SELECT SUM(quantity) FROM order_items WHERE order_id = o.id) AS item_count,
              (SELECT product_name FROM order_items WHERE order_id = o.id ORDER BY id LIMIT 1) AS first_item,
              (SELECT image_url FROM order_items WHERE order_id = o.id ORDER BY id LIMIT 1) AS first_image,
              d.delivery_status, s.first_name AS staff_first, s.last_name AS staff_last
            FROM orders o
            LEFT JOIN deliveries d ON d.order_id = o.id
            LEFT JOIN users s ON s.id = d.staff_id""";

    public record ListFilter(Integer userId, String status, String search, String from, String to) {}

    public Map<String, Object> list(ListFilter f, Paging paging) {
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (f.userId() != null) { where.add("o.user_id = ?"); params.add(f.userId()); }
        if ("active".equals(f.status())) where.add("o.status NOT IN ('delivered','cancelled')");
        else if (f.status() != null) { where.add("o.status = ?"); params.add(f.status()); }
        if (!Text.isBlank(f.search())) {
            where.add("(o.order_number ILIKE ? OR o.customer_name ILIKE ? OR o.phone ILIKE ?)");
            String like = "%" + f.search().trim() + "%";
            params.add(like); params.add(like); params.add(like);
        }
        if (f.from() != null) { where.add("o.created_at >= ?::date"); params.add(f.from()); }
        if (f.to() != null) { where.add("o.created_at < (?::date + INTERVAL '1 day')"); params.add(f.to()); }
        String whereSql = where.isEmpty() ? "" : "WHERE " + String.join(" AND ", where);
        Object[] args = params.toArray();
        var items = db.rows(LIST_SELECT + " " + whereSql + " ORDER BY o.created_at DESC LIMIT " + paging.limit() + " OFFSET " + paging.offset(), args)
                .stream().map(OrderService::toOrder).toList();
        return paging.result(items, db.count("SELECT COUNT(*) FROM orders o " + whereSql, args));
    }

    public Map<String, Object> get(int id, AuthUser user) {
        Row o = db.one(LIST_SELECT + " WHERE o.id = ?", id).orElseThrow(() -> ApiException.notFound("Order not found"));
        // Object-level authorization: customers only ever see their own orders.
        if (o.integer("user_id") != user.id() && !Permissions.has(user.role(), Permissions.ORDER_READ_ANY)) {
            throw ApiException.notFound("Order not found");
        }
        var items = db.rows("SELECT oi.*, p.slug FROM order_items oi LEFT JOIN products p ON p.id = oi.product_id WHERE oi.order_id = ? ORDER BY oi.id", id)
                .stream().map(i -> Json.obj("id", i.integer("id"), "productId", i.integer("product_id"), "slug", i.str("slug"),
                        "name", i.str("product_name"), "imageUrl", i.str("image_url"), "quantity", i.intOr0("quantity"),
                        "price", i.money("price"), "lineTotal", i.money("price") * i.intOr0("quantity")))
                .toList();
        var history = db.rows("""
                SELECT h.*, u.first_name, u.last_name, u.role FROM order_status_history h LEFT JOIN users u ON u.id = h.changed_by
                WHERE h.order_id = ? ORDER BY h.created_at, h.id""", id)
                .stream().map(h -> Json.obj("status", h.str("status"), "note", h.str("note"), "at", h.ts("created_at"),
                        "by", h.str("first_name") == null ? null : h.str("first_name") + " " + h.str("last_name"), "byRole", h.str("role")))
                .toList();
        var delivery = db.one("""
                SELECT d.*, u.first_name, u.last_name, u.phone AS staff_phone FROM deliveries d LEFT JOIN users u ON u.id = d.staff_id
                WHERE d.order_id = ?""", id)
                .map(d -> Json.obj("id", d.integer("id"), "status", d.str("delivery_status"), "staffId", d.integer("staff_id"),
                        "staffName", d.str("first_name") == null ? null : d.str("first_name") + " " + d.str("last_name"),
                        "staffPhone", d.str("staff_phone"), "notes", d.str("notes"), "deliveredAt", d.ts("delivered_at")))
                .orElse(null);
        var paymentList = db.rows("SELECT * FROM payments WHERE order_id = ? ORDER BY payment_date DESC", id)
                .stream().map(PaymentService::toPayment).toList();
        return Json.with(toOrder(o), "items", items, "history", history, "delivery", delivery, "payments", paymentList);
    }

    // ---------------------------------------------------------------------------------- status changes

    /** Applies a status change inside an open transaction; returns the updated order row. */
    public Row applyStatus(Row order, String next, int actorId, String note) {
        String current = order.str("status");
        if (!canTransition(current, next)) throw ApiException.badRequest("Cannot move an order from \"" + current + "\" to \"" + next + "\"");
        int id = order.integer("id");
        db.update("UPDATE orders SET status = ?, updated_at = NOW() WHERE id = ?", next, id);
        db.update("INSERT INTO order_status_history (order_id, status, changed_by, note) VALUES (?,?,?,?)", id, next, actorId, Text.blankToNull(note));
        switch (next) {
            case "cancelled" -> {
                // return stock and refund anything already paid
                db.update("""
                        UPDATE products p SET stock = p.stock + oi.quantity, sold_count = GREATEST(p.sold_count - oi.quantity, 0)
                        FROM order_items oi WHERE oi.order_id = ? AND oi.product_id = p.id""", id);
                db.update("UPDATE payments SET payment_status = 'refunded' WHERE order_id = ? AND payment_status = 'paid'", id);
                db.update("UPDATE payments SET payment_status = 'failed' WHERE order_id = ? AND payment_status = 'pending'", id);
                db.update("UPDATE orders SET payment_status = CASE WHEN payment_status = 'paid' THEN 'refunded' ELSE 'failed' END WHERE id = ?", id);
                cache.invalidate("products:");
            }
            case "out_for_delivery" -> db.update("""
                    UPDATE deliveries SET delivery_status = 'on_the_way', updated_at = NOW()
                    WHERE order_id = ? AND delivery_status IN ('pending','assigned','picked_up')""", id);
            case "delivered" -> {
                db.update("UPDATE deliveries SET delivery_status = 'delivered', delivered_at = NOW(), updated_at = NOW() WHERE order_id = ?", id);
                // cash collected at the door
                db.update("UPDATE payments SET payment_status = 'paid', payment_date = NOW() WHERE order_id = ? AND payment_status = 'pending'", id);
                db.update("UPDATE orders SET payment_status = 'paid' WHERE id = ? AND payment_status = 'pending'", id);
            }
            default -> { }
        }
        return db.one("SELECT * FROM orders WHERE id = ?", id).orElseThrow();
    }

    public void announceStatus(Row order, int actorId, String note) {
        events.publish("order.status_changed", Json.obj(
                "actorId", actorId, "entity", "order", "entityId", order.integer("id"), "userId", order.integer("user_id"),
                "orderId", order.integer("id"), "orderNumber", order.str("order_number"), "customerName", order.str("customer_name"),
                "email", order.str("email"), "phone", order.str("phone"), "status", order.str("status"), "note", note));
    }

    public Map<String, Object> updateStatus(int orderId, String next, AuthUser actor, String note) {
        Row updated = tx.execute(s -> {
            Row order = db.one("SELECT * FROM orders WHERE id = ? FOR UPDATE", orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
            return applyStatus(order, next, actor.id(), note);
        });
        announceStatus(updated, actor.id(), note);
        return get(orderId, actor);
    }

    public Map<String, Object> cancelOwn(int orderId, AuthUser user) {
        Row o = db.one("SELECT user_id, status FROM orders WHERE id = ?", orderId)
                .filter(r -> r.integer("user_id") == user.id())
                .orElseThrow(() -> ApiException.notFound("Order not found"));
        if (!"pending".equals(o.str("status"))) {
            throw new ApiException(HttpStatus.CONFLICT, "This order is already being prepared and can no longer be cancelled online. Please contact us.");
        }
        return updateStatus(orderId, "cancelled", user, "Cancelled by customer");
    }

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
