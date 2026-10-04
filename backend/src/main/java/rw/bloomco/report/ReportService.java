package rw.bloomco.report;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import rw.bloomco.common.Db;
import rw.bloomco.common.Json;
import rw.bloomco.common.Row;
import rw.bloomco.document.DocumentStore;

/** Dashboard statistics, chart series and CSV export. */
@Service
public class ReportService {

    private static final String REVENUE = "status <> 'cancelled'";
    private final Db db;
    private final DocumentStore documents;

    public ReportService(Db db, DocumentStore documents) {
        this.db = db;
        this.documents = documents;
    }

    public Map<String, Object> adminDashboard() {
        Row s = db.one("""
                SELECT
                  (SELECT COALESCE(SUM(total_amount),0) FROM orders WHERE %1$s) AS total_sales,
                  (SELECT COALESCE(SUM(total_amount),0) FROM orders WHERE %1$s AND created_at >= date_trunc('month', NOW())) AS month_sales,
                  (SELECT COUNT(*) FROM orders) AS total_orders,
                  (SELECT COUNT(*) FROM users WHERE role = 'customer') AS total_customers,
                  (SELECT COUNT(*) FROM products) AS total_products,
                  (SELECT COUNT(*) FROM orders WHERE status IN ('pending','confirmed','preparing','ready','out_for_delivery')) AS pending_orders,
                  (SELECT COUNT(*) FROM orders WHERE status = 'delivered') AS delivered_orders,
                  (SELECT COUNT(*) FROM users WHERE role = 'staff') AS total_staff,
                  (SELECT COALESCE(AVG(total_amount),0) FROM orders WHERE %1$s) AS avg_order_value""".formatted(REVENUE)).orElseThrow();
        var paymentMix = db.rows("""
                SELECT payment_method AS method, COUNT(*) AS orders, COALESCE(SUM(total_amount),0) AS amount
                FROM orders WHERE %s GROUP BY payment_method ORDER BY amount DESC""".formatted(REVENUE)).stream()
                .map(r -> Json.obj("method", r.str("method"), "orders", r.longOr0("orders"), "amount", r.money("amount"))).toList();
        var lowStock = db.rows("SELECT id, name, stock FROM products WHERE stock <= 5 AND status = 'active' ORDER BY stock LIMIT 6").stream()
                .map(r -> Json.obj("id", r.integer("id"), "name", r.str("name"), "stock", r.intOr0("stock"))).toList();
        return Json.obj(
                "stats", Json.obj(
                        "totalSales", s.money("total_sales"), "monthSales", s.money("month_sales"), "totalOrders", s.longOr0("total_orders"),
                        "totalCustomers", s.longOr0("total_customers"), "totalProducts", s.longOr0("total_products"),
                        "pendingOrders", s.longOr0("pending_orders"), "deliveredOrders", s.longOr0("delivered_orders"),
                        "totalStaff", s.longOr0("total_staff"), "avgOrderValue", s.money("avg_order_value"),
                        "newMessages", documents.countNewContacts()),
                "salesOverTime", salesOverTime(30),
                "popularFlowers", popularFlowers(6),
                "ordersByMonth", ordersByMonth(12),
                "deliveryPerformance", deliveryPerformance(),
                "revenueByCategory", revenueByCategory(),
                "paymentMix", paymentMix,
                "lowStock", lowStock);
    }

    public List<Map<String, Object>> salesOverTime(int days) {
        return db.rows("""
                SELECT to_char(d, 'YYYY-MM-DD') AS date, COALESCE(SUM(o.total_amount), 0) AS revenue, COUNT(o.id) AS orders
                FROM generate_series(CURRENT_DATE - (?::int - 1), CURRENT_DATE, INTERVAL '1 day') d
                LEFT JOIN orders o ON o.created_at::date = d::date AND o.%s
                GROUP BY d ORDER BY d""".formatted(REVENUE), days).stream()
                .map(r -> Json.obj("date", r.str("date"), "revenue", r.money("revenue"), "orders", r.longOr0("orders"))).toList();
    }

    public List<Map<String, Object>> ordersByMonth(int months) {
        return db.rows("""
                SELECT to_char(m, 'Mon YY') AS month, COUNT(o.id) AS orders,
                       COALESCE(SUM(o.total_amount) FILTER (WHERE o.%s), 0) AS revenue
                FROM generate_series(date_trunc('month', NOW()) - ((?::int - 1) || ' months')::interval, date_trunc('month', NOW()), INTERVAL '1 month') m
                LEFT JOIN orders o ON date_trunc('month', o.created_at) = m
                GROUP BY m ORDER BY m""".formatted(REVENUE), months).stream()
                .map(r -> Json.obj("month", r.str("month"), "orders", r.longOr0("orders"), "revenue", r.money("revenue"))).toList();
    }

    public List<Map<String, Object>> popularFlowers(int limit) {
        return db.rows("""
                SELECT oi.product_name AS name, SUM(oi.quantity) AS quantity, SUM(oi.quantity * oi.price) AS revenue
                FROM order_items oi JOIN orders o ON o.id = oi.order_id WHERE o.%s
                GROUP BY oi.product_name ORDER BY quantity DESC LIMIT ?""".formatted(REVENUE), limit).stream()
                .map(r -> Json.obj("name", r.str("name"), "quantity", r.longOr0("quantity"), "revenue", r.money("revenue"))).toList();
    }

    public List<Map<String, Object>> revenueByCategory() {
        return db.rows("""
                SELECT COALESCE(c.name, 'Uncategorised') AS category, SUM(oi.quantity * oi.price) AS revenue
                FROM order_items oi JOIN orders o ON o.id = oi.order_id
                LEFT JOIN products p ON p.id = oi.product_id LEFT JOIN categories c ON c.id = p.category_id
                WHERE o.%s GROUP BY c.name ORDER BY revenue DESC""".formatted(REVENUE)).stream()
                .map(r -> Json.obj("category", r.str("category"), "revenue", r.money("revenue"))).toList();
    }

    public Map<String, Object> deliveryPerformance() {
        Row r = db.one("""
                SELECT
                  COUNT(*) FILTER (WHERE d.delivery_status = 'delivered') AS delivered,
                  COUNT(*) FILTER (WHERE d.delivery_status = 'delivered' AND d.delivered_at::date <= d.delivery_date) AS on_time,
                  COUNT(*) FILTER (WHERE d.delivery_status IN ('assigned','picked_up','on_the_way')) AS in_progress,
                  COUNT(*) FILTER (WHERE d.delivery_status = 'pending') AS unassigned,
                  COUNT(*) FILTER (WHERE d.delivery_status <> 'delivered' AND d.delivery_date < CURRENT_DATE) AS late
                FROM deliveries d JOIN orders o ON o.id = d.order_id WHERE o.status <> 'cancelled'""").orElseThrow();
        long delivered = r.longOr0("delivered");
        long onTime = r.longOr0("on_time");
        return Json.obj("delivered", delivered, "on_time", onTime, "in_progress", r.longOr0("in_progress"),
                "unassigned", r.longOr0("unassigned"), "late", r.longOr0("late"),
                "onTimeRate", delivered == 0 ? 100 : Math.round(onTime * 100.0 / delivered));
    }

    public Map<String, Object> staffDashboard(int staffId) {
        Row s = db.one("""
                SELECT
                  COUNT(*) FILTER (WHERE status = 'pending') AS new_orders,
                  COUNT(*) FILTER (WHERE status IN ('confirmed','preparing')) AS preparing,
                  COUNT(*) FILTER (WHERE status = 'ready') AS ready,
                  COUNT(*) FILTER (WHERE status = 'out_for_delivery') AS out_for_delivery,
                  COUNT(*) FILTER (WHERE status = 'delivered') AS completed,
                  COUNT(*) FILTER (WHERE status = 'delivered' AND updated_at::date = CURRENT_DATE) AS completed_today
                FROM orders""").orElseThrow();
        var today = db.rows("""
                SELECT d.id, d.delivery_time, d.delivery_status, d.delivery_address, o.order_number, o.customer_name, o.id AS order_id,
                       u.first_name AS staff_first
                FROM deliveries d JOIN orders o ON o.id = d.order_id LEFT JOIN users u ON u.id = d.staff_id
                WHERE d.delivery_date = CURRENT_DATE AND o.status <> 'cancelled' ORDER BY d.delivery_time""");
        long mine = db.count("""
                SELECT COUNT(*) FROM deliveries WHERE staff_id = ? AND delivery_status IN ('assigned','picked_up','on_the_way')""", staffId);
        return Json.obj(
                "stats", Json.obj("newOrders", s.longOr0("new_orders"), "preparing", s.longOr0("preparing"), "ready", s.longOr0("ready"),
                        "outForDelivery", s.longOr0("out_for_delivery"), "completed", s.longOr0("completed"),
                        "completedToday", s.longOr0("completed_today"), "todaysDeliveries", today.size(),
                        "myActiveDeliveries", mine, "newMessages", documents.countNewContacts()),
                "todaysDeliveries", today.stream().map(d -> Json.obj("id", d.integer("id"), "orderId", d.integer("order_id"),
                        "orderNumber", d.str("order_number"), "customer", d.str("customer_name"), "time", d.str("delivery_time"),
                        "status", d.str("delivery_status"), "address", d.str("delivery_address"), "courier", d.str("staff_first"))).toList());
    }

    public String ordersCsv(String from, String to) {
        List<String> header = List.of("order_number", "date", "customer_name", "phone", "status", "payment_method", "payment_status",
                "subtotal", "discount", "delivery_fee", "gift_total", "total_amount", "delivery_date");
        var rows = db.rows("""
                SELECT order_number, created_at::date AS date, customer_name, phone, status, payment_method, payment_status,
                       subtotal, discount, delivery_fee, gift_total, total_amount, delivery_date
                FROM orders WHERE created_at::date BETWEEN ?::date AND ?::date ORDER BY created_at""", from, to);
        StringBuilder sb = new StringBuilder(String.join(",", header));
        for (Row r : rows) {
            sb.append('\n').append(header.stream().map(h -> {
                Object v = r.raw(h);
                if (v instanceof java.math.BigDecimal bd) v = bd.stripTrailingZeros().toPlainString();
                return "\"" + String.valueOf(v == null ? "" : v).replace("\"", "\"\"") + "\"";
            }).collect(Collectors.joining(",")));
        }
        return sb.toString();
    }
}
