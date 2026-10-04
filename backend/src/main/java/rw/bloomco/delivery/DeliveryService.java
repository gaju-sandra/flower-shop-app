package rw.bloomco.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import rw.bloomco.common.ApiException;
import rw.bloomco.common.Db;
import rw.bloomco.common.Json;
import rw.bloomco.common.Paging;
import rw.bloomco.common.Row;
import rw.bloomco.common.Text;
import rw.bloomco.messaging.EventBus;
import rw.bloomco.order.OrderService;
import rw.bloomco.security.AuthUser;

/** Delivery assignment and courier progress (kept in sync with the order status). */
@Service
public class DeliveryService {

    public static final Map<String, Set<String>> FLOW = Map.of(
            "pending", Set.of("assigned"),
            "assigned", Set.of("picked_up", "assigned"),
            "picked_up", Set.of("on_the_way"),
            "on_the_way", Set.of("delivered"),
            "delivered", Set.of());

    private static final String SELECT = """
            SELECT d.*, o.order_number, o.status AS order_status, o.customer_name, o.phone, o.recipient_name,
                   o.location_description, o.instructions, o.total_amount, o.payment_method, o.payment_status,
                   u.first_name AS staff_first, u.last_name AS staff_last
            FROM deliveries d JOIN orders o ON o.id = d.order_id LEFT JOIN users u ON u.id = d.staff_id""";

    private final Db db;
    private final OrderService orders;
    private final EventBus events;
    private final TransactionTemplate tx;

    public DeliveryService(Db db, OrderService orders, EventBus events, TransactionTemplate tx) {
        this.db = db;
        this.orders = orders;
        this.events = events;
        this.tx = tx;
    }

    static Map<String, Object> toDelivery(Row d) {
        String first = d.str("staff_first");
        return Json.obj(
                "id", d.integer("id"),
                "orderId", d.integer("order_id"),
                "orderNumber", d.str("order_number"),
                "orderStatus", d.str("order_status"),
                "customer", d.str("customer_name"),
                "phone", d.str("phone"),
                "recipientName", d.str("recipient_name"),
                "address", d.str("delivery_address"),
                "locationDescription", d.str("location_description"),
                "instructions", d.str("instructions"),
                "date", d.date("delivery_date"),
                "time", d.str("delivery_time"),
                "status", d.str("delivery_status"),
                "staffId", d.integer("staff_id"),
                "staffName", first == null ? null : first + " " + d.str("staff_last"),
                "notes", d.str("notes"),
                "total", d.money("total_amount"),
                "paymentMethod", d.str("payment_method"),
                "paymentStatus", d.str("payment_status"),
                "deliveredAt", d.ts("delivered_at"));
    }

    public Map<String, Object> list(String status, String date, Integer staffId, Paging paging) {
        List<String> where = new ArrayList<>(List.of("o.status <> 'cancelled'"));
        List<Object> params = new ArrayList<>();
        if (status != null) { where.add("d.delivery_status = ?"); params.add(status); }
        if (date != null) { where.add("d.delivery_date = ?::date"); params.add(date); }
        if (staffId != null) { where.add("d.staff_id = ?"); params.add(staffId); }
        String whereSql = "WHERE " + String.join(" AND ", where);
        Object[] args = params.toArray();
        var items = db.rows(SELECT + " " + whereSql + " ORDER BY (d.delivery_status = 'delivered'), d.delivery_date, d.delivery_time LIMIT "
                + paging.limit() + " OFFSET " + paging.offset(), args).stream().map(DeliveryService::toDelivery).toList();
        return paging.result(items, db.count("SELECT COUNT(*) FROM deliveries d JOIN orders o ON o.id = d.order_id " + whereSql, args));
    }

    public List<Map<String, Object>> couriers() {
        return db.rows("""
                SELECT u.id, u.first_name, u.last_name, u.phone, u.staff_role,
                  (SELECT COUNT(*) FROM deliveries d WHERE d.staff_id = u.id AND d.delivery_status IN ('assigned','picked_up','on_the_way')) AS active_jobs
                FROM users u WHERE u.role = 'staff' AND u.status = 'active'
                ORDER BY (u.staff_role = 'delivery_staff') DESC, u.first_name""").stream()
                .map(r -> Json.obj("id", r.integer("id"), "name", r.str("first_name") + " " + r.str("last_name"), "phone", r.str("phone"),
                        "staffRole", r.str("staff_role"), "activeJobs", r.longOr0("active_jobs")))
                .toList();
    }

    private Row loadForUpdate(int deliveryId) {
        return db.one("""
                SELECT d.*, o.status AS order_status FROM deliveries d JOIN orders o ON o.id = d.order_id
                WHERE d.id = ? FOR UPDATE OF d, o""", deliveryId).orElseThrow(() -> ApiException.notFound("Delivery not found"));
    }

    public Map<String, Object> assign(int deliveryId, int staffId, int actorId) {
        Row[] result = tx.execute(s -> {
            Row d = loadForUpdate(deliveryId);
            if (!Set.of("pending", "assigned").contains(d.str("delivery_status"))) throw ApiException.badRequest("This delivery is already on its way");
            if ("cancelled".equals(d.str("order_status"))) throw ApiException.badRequest("This order was cancelled");
            Row staff = db.one("SELECT * FROM users WHERE id = ? AND role = 'staff' AND status = 'active'", staffId)
                    .orElseThrow(() -> ApiException.badRequest("Choose an active staff member"));
            Row updated = db.rows("UPDATE deliveries SET staff_id = ?, delivery_status = 'assigned', updated_at = NOW() WHERE id = ? RETURNING *",
                    staffId, deliveryId).get(0);
            return new Row[] {updated, staff};
        });
        Row delivery = result[0];
        String orderNumber = db.one("SELECT order_number FROM orders WHERE id = ?", delivery.integer("order_id")).map(r -> r.str("order_number")).orElse(null);
        events.publish("delivery.assigned", Json.obj(
                "actorId", actorId, "entity", "delivery", "entityId", deliveryId, "orderNumber", orderNumber,
                "staffId", staffId, "staffPhone", result[1].str("phone"), "address", delivery.str("delivery_address"),
                "deliveryDate", delivery.date("delivery_date"), "deliveryTime", delivery.str("delivery_time")));
        return get(deliveryId);
    }

    /**
     * Courier progress. Keeps the order in sync:
     *   picked_up -> requires the order to be "ready", moves it to out_for_delivery
     *   delivered -> order delivered (and cash-on-delivery marked paid)
     */
    public Map<String, Object> updateStatus(int deliveryId, String next, AuthUser actor, String notes) {
        Row changedOrder = tx.execute(s -> {
            Row d = loadForUpdate(deliveryId);
            String current = d.str("delivery_status");
            if (!FLOW.getOrDefault(current, Set.of()).contains(next)) {
                throw ApiException.badRequest("Cannot move a delivery from \"" + current + "\" to \"" + next + "\"");
            }
            if ("picked_up".equals(next) && !Set.of("ready", "out_for_delivery").contains(d.str("order_status"))) {
                throw ApiException.badRequest("The bouquet must be marked \"Ready\" before it can be picked up");
            }
            db.update("""
                    UPDATE deliveries SET delivery_status = ?, notes = COALESCE(?, notes), updated_at = NOW(),
                      delivered_at = CASE WHEN ? = 'delivered' THEN NOW() ELSE delivered_at END
                    WHERE id = ?""", next, Text.blankToNull(notes), next, deliveryId);
            Row order = db.one("SELECT * FROM orders WHERE id = ?", d.integer("order_id")).orElseThrow();
            String orderStatus = order.str("status");
            if ("picked_up".equals(next) && "ready".equals(orderStatus)) {
                return orders.applyStatus(order, "out_for_delivery", actor.id(), "Courier picked up the flowers");
            }
            if ("delivered".equals(next) && !"delivered".equals(orderStatus)) {
                if ("ready".equals(orderStatus)) order = orders.applyStatus(order, "out_for_delivery", actor.id(), null);
                return orders.applyStatus(order, "delivered", actor.id(), Text.isBlank(notes) ? "Delivered to recipient" : notes);
            }
            return null;
        });
        if (changedOrder != null) orders.announceStatus(changedOrder, actor.id(), null);
        events.publish("delivery.status_changed", Json.obj("actorId", actor.id(), "entity", "delivery", "entityId", deliveryId, "status", next));
        return get(deliveryId);
    }

    public Map<String, Object> get(int id) {
        return db.one(SELECT + " WHERE d.id = ?", id).map(DeliveryService::toDelivery)
                .orElseThrow(() -> ApiException.notFound("Delivery not found"));
    }
}
