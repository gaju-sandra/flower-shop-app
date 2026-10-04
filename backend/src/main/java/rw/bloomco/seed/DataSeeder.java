package rw.bloomco.seed;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import rw.bloomco.common.Db;
import rw.bloomco.common.Text;
import rw.bloomco.config.AppProperties;
import rw.bloomco.order.OrderService;
import rw.bloomco.order.Pricing;

/**
 * Loads demo data into an empty database on startup (app.seed-on-startup=true).
 * Run with --reset-demo-data to wipe every table and re-seed.
 *
 * Demo accounts (local development only):
 *   admin@bloomandco.rw         Admin@123
 *   aline.staff@bloomandco.rw   Staff@123     (and the other staff in SeedData)
 *   melissa@example.com         Customer@123  (and the other customers in SeedData)
 */
@Component
@Order(1)
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final Db db;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;
    private final AppProperties props;

    public DataSeeder(Db db, PasswordEncoder encoder, TransactionTemplate tx, AppProperties props) {
        this.db = db;
        this.encoder = encoder;
        this.tx = tx;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean reset = args.containsOption("reset-demo-data");
        if (!props.seedOnStartup() && !reset) return;
        if (db.count("SELECT COUNT(*) FROM users") > 0 && !reset) return;
        tx.executeWithoutResult(s -> seed(reset));
        log.info("🌸 Seed complete. Demo logins: admin@bloomandco.rw / Admin@123 · aline.staff@bloomandco.rw / Staff@123 · melissa@example.com / Customer@123");
    }

    // deterministic PRNG (mulberry32) so every seed produces the same demo data
    private int state = 20261004;

    private double rand() {
        state += 0x6d2b79f5;
        int t = (state ^ (state >>> 15)) * (1 | state);
        t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
        return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
    }

    private <T> T pick(List<T> list) {
        return list.get((int) Math.floor(rand() * list.size()));
    }

    private int between(int min, int max) {
        return min + (int) Math.floor(rand() * (max - min + 1));
    }

    private record SeededProduct(int id, String name, long price, int discount, String imageUrl) {}

    private record SeededCustomer(int id, SeedData.Customer c) {
        String name() {
            return c.first() + " " + c.last();
        }
    }

    private void seed(boolean reset) {
        if (reset) {
            db.update("""
                    TRUNCATE users, categories, products, promotions, gift_options, settings, orders, payments, deliveries, reviews,
                      wishlist, cart, cart_items, addresses, refresh_tokens, password_resets, order_items, order_status_history
                    RESTART IDENTITY CASCADE""");
        }
        String adminHash = encoder.encode("Admin@123");
        String staffHash = encoder.encode("Staff@123");
        String customerHash = encoder.encode("Customer@123");

        // ---- users ----
        db.update("""
                INSERT INTO users (first_name, last_name, email, phone, password_hash, role)
                VALUES ('Sandra', 'Gaju', 'admin@bloomandco.rw', '0788000100', ?, 'admin')""", adminHash);
        int courierId = 0;
        int managerId = 0;
        for (var s : SeedData.STAFF) {
            int id = db.insertId("""
                    INSERT INTO users (first_name, last_name, email, phone, password_hash, role, staff_role)
                    VALUES (?,?,?,?,?,'staff',?) RETURNING id""", s.first(), s.last(), s.email(), s.phone(), staffHash, s.staffRole());
            if ("delivery_staff".equals(s.staffRole())) courierId = id;
            if ("order_manager".equals(s.staffRole())) managerId = id;
        }
        List<SeededCustomer> customers = new ArrayList<>();
        for (int i = 0; i < SeedData.CUSTOMERS.size(); i++) {
            var c = SeedData.CUSTOMERS.get(i);
            Instant created = Instant.now().minus(360 - i * 20L, ChronoUnit.DAYS);
            int id = db.insertId("""
                    INSERT INTO users (first_name, last_name, email, phone, password_hash, role, created_at)
                    VALUES (?,?,?,?,?,'customer',?) RETURNING id""", c.first(), c.last(), c.email(), c.phone(), customerHash, Timestamp.from(created));
            db.update("INSERT INTO cart (user_id) VALUES (?)", id);
            db.update("""
                    INSERT INTO addresses (user_id, label, recipient_name, phone, province, district, sector, street, is_default)
                    VALUES (?,'Home',?,?,?,?,?,?,TRUE)""", id, c.first() + " " + c.last(), c.phone(), c.province(), c.district(), c.sector(), c.street());
            customers.add(new SeededCustomer(id, c));
        }

        // ---- catalogue ----
        Map<String, Integer> categoryIds = new HashMap<>();
        for (var c : SeedData.CATEGORIES) {
            categoryIds.put(c.name(), db.insertId("INSERT INTO categories (name, slug, description) VALUES (?,?,?) RETURNING id",
                    c.name(), Text.slugify(c.name()), c.description()));
        }
        List<SeededProduct> products = new ArrayList<>();
        int n = SeedData.PRODUCTS.size();
        for (int i = 0; i < n; i++) {
            var p = SeedData.PRODUCTS.get(i);
            Instant created = Instant.now().minus((long) (n - i) * 9, ChronoUnit.DAYS);
            String imageUrl = SeedData.img(p.imageId());
            int id = db.insertId("""
                    INSERT INTO products (name, slug, description, category_id, occasions, price, discount_percent, stock, image_url, sold_count, created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id""",
                    p.name(), Text.slugify(p.name()), p.description(), categoryIds.get(p.category()), db.textArray(p.occasions()),
                    p.price(), p.discount(), p.stock(), imageUrl, p.sold(), Timestamp.from(created));
            products.add(new SeededProduct(id, p.name(), p.price(), p.discount(), imageUrl));
        }
        for (var g : SeedData.GIFT_OPTIONS) {
            db.update("INSERT INTO gift_options (code, name, icon, price) VALUES (?,?,?,?)", g.code(), g.name(), g.icon(), g.price());
        }
        for (var p : SeedData.PROMOTIONS) {
            db.update("""
                    INSERT INTO promotions (code, title, description, discount_percent, min_order, starts_at, ends_at)
                    VALUES (?,?,?,?,?, CURRENT_DATE - 30, CURRENT_DATE + 120)""", p.code(), p.title(), p.description(), p.percent(), p.minOrder());
        }

        // ---- order history (12 months, busier around February) ----
        Map<Integer, Set<Integer>> delivered = new LinkedHashMap<>();
        List<String> flow = List.of("pending", "confirmed", "preparing", "ready", "out_for_delivery", "delivered");
        Map<String, String> deliveryStatusFor = Map.of("pending", "pending", "confirmed", "pending", "preparing", "assigned",
                "ready", "assigned", "out_for_delivery", "on_the_way", "delivered", "delivered", "cancelled", "pending");
        Map<String, String> prefix = Map.of("mtn_momo", "MOMO", "airtel_money", "AIRTEL", "card", "CARD", "cash_on_delivery", "COD");
        long now = System.currentTimeMillis();

        for (int k = 0; k < 110; k++) {
            int daysAgo = k < 12 ? between(0, 4) : (int) Math.floor(Math.pow(rand(), 1.4) * 360);
            Instant created = Instant.ofEpochMilli(now - daysAgo * 86_400_000L - between(1, 10) * 3_600_000L);
            if (created.atZone(ZoneOffset.UTC).getMonth() != Month.FEBRUARY && rand() < 0.15) continue; // thin out non-Feb months
            SeededCustomer customer = pick(customers);
            int lineCount = between(1, 3);
            Map<Integer, int[]> lines = new LinkedHashMap<>(); // productIndex -> qty
            for (int j = 0; j < lineCount; j++) {
                int idx = (int) Math.floor(rand() * products.size());
                int qty = between(1, 2);
                lines.putIfAbsent(idx, new int[] {qty});
            }
            long subtotal = 0;
            for (var e : lines.entrySet()) {
                var p = products.get(e.getKey());
                subtotal += Pricing.unitPrice(p.price(), p.discount()) * e.getValue()[0];
            }
            long deliveryFee = subtotal >= 50000 ? 0 : 2000;
            boolean withCard = rand() < 0.35;
            long giftTotal = withCard ? 1500 : 0;
            String gifts = withCard ? "[{\"code\":\"greeting_card\",\"name\":\"Greeting card\",\"icon\":\"💌\",\"price\":1500}]" : "[]";
            long total = subtotal + deliveryFee + giftTotal;
            LocalDate deliveryDate = LocalDate.ofInstant(created.plus(between(0, 2), ChronoUnit.DAYS), ZoneOffset.UTC);
            String method = pick(SeedData.METHODS);

            String status;
            if (daysAgo > 4) status = rand() < 0.92 ? "delivered" : "cancelled";
            else status = pick(List.of("pending", "pending", "confirmed", "preparing", "ready", "out_for_delivery", "delivered"));
            boolean paid = "delivered".equals(status) || !"cash_on_delivery".equals(method);
            String paymentStatus = "cancelled".equals(status) ? ("cash_on_delivery".equals(method) ? "failed" : "refunded") : paid ? "paid" : "pending";
            String[] msg = rand() < 0.6 ? pick(SeedData.MESSAGES) : null;
            var c = customer.c();
            String address = c.street() + ", " + c.sector() + ", " + c.district() + ", " + c.province();

            int orderId = db.insertId("""
                    INSERT INTO orders (user_id, subtotal, discount, gift_total, delivery_fee, total_amount, customer_name, email, phone,
                      province, district, sector, street, delivery_address, delivery_date, delivery_time, recipient_name, message_type,
                      gift_message, gift_options, payment_method, status, payment_status, created_at, updated_at)
                    VALUES (?,?,0,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?) RETURNING id""",
                    customer.id(), subtotal, giftTotal, deliveryFee, total, customer.name(), c.email(), c.phone(),
                    c.province(), c.district(), c.sector(), c.street(), address, java.sql.Date.valueOf(deliveryDate), pick(SeedData.SLOTS),
                    msg != null ? pick(SeedData.RECIPIENTS) : null, msg != null ? msg[0] : null, msg != null ? msg[1] : null,
                    gifts, method, status, paymentStatus, Timestamp.from(created), Timestamp.from(created));
            db.update("UPDATE orders SET order_number = ? WHERE id = ?",
                    OrderService.formatOrderNumber(orderId, created.atZone(ZoneOffset.UTC).getYear()), orderId);
            for (var e : lines.entrySet()) {
                var p = products.get(e.getKey());
                db.update("INSERT INTO order_items (order_id, product_id, product_name, image_url, quantity, price) VALUES (?,?,?,?,?,?)",
                        orderId, p.id(), p.name(), p.imageUrl(), e.getValue()[0], Pricing.unitPrice(p.price(), p.discount()));
            }
            List<String> reached = "cancelled".equals(status) ? List.of("pending", "cancelled") : flow.subList(0, flow.indexOf(status) + 1);
            for (int r = 0; r < reached.size(); r++) {
                db.update("INSERT INTO order_status_history (order_id, status, changed_by, created_at) VALUES (?,?,?,?)",
                        orderId, reached.get(r), r == 0 ? customer.id() : managerId, Timestamp.from(created.plus(r * 2L, ChronoUnit.HOURS)));
            }
            String deliveryStatus = deliveryStatusFor.get(status);
            Timestamp deliveredAt = "delivered".equals(status)
                    ? Timestamp.from(deliveryDate.atStartOfDay(ZoneOffset.UTC).toInstant().plus(rand() < 0.9 ? 10 : 34, ChronoUnit.HOURS))
                    : null;
            db.update("""
                    INSERT INTO deliveries (order_id, staff_id, delivery_address, delivery_date, delivery_time, delivery_status, delivered_at)
                    VALUES (?,?,?,?,(SELECT delivery_time FROM orders WHERE id = ?),?,?)""",
                    orderId, "pending".equals(deliveryStatus) ? null : courierId, address, java.sql.Date.valueOf(deliveryDate), orderId,
                    deliveryStatus, deliveredAt);
            db.update("""
                    INSERT INTO payments (order_id, payment_method, amount, transaction_reference, payer_phone, card_last4, payment_status, payment_date)
                    VALUES (?,?,?,?,?,?,?,?)""",
                    orderId, method, total, prefix.get(method) + "-SEED" + String.format("%05d", orderId),
                    method.contains("money") || "mtn_momo".equals(method) ? c.phone() : null, "card".equals(method) ? "4242" : null,
                    paymentStatus, Timestamp.from(created));
            if ("delivered".equals(status)) {
                var set = delivered.computeIfAbsent(customer.id(), x -> new LinkedHashSet<>());
                lines.keySet().forEach(idx -> set.add(products.get(idx).id()));
            }
        }

        // ---- reviews for delivered flowers ----
        for (var e : delivered.entrySet()) {
            e.getValue().stream().limit(4).forEach(productId -> {
                String[] review = pick(SeedData.REVIEW_TEXTS);
                db.update("INSERT INTO reviews (user_id, product_id, rating, comment) VALUES (?,?,?,?)",
                        e.getKey(), productId, Integer.parseInt(review[0]), review[1]);
            });
        }
        db.update("""
                UPDATE products p SET rating_avg = s.avg, rating_count = s.n
                FROM (SELECT product_id, ROUND(AVG(rating)::numeric, 2) AS avg, COUNT(*) AS n FROM reviews GROUP BY product_id) s
                WHERE s.product_id = p.id""");

        // a little wishlist activity for the demo customer
        int melissa = customers.get(0).id();
        for (var p : products.subList(5, 8)) db.update("INSERT INTO wishlist (user_id, product_id) VALUES (?,?)", melissa, p.id());
    }
}
