package rw.bloomco.cart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import rw.bloomco.common.ApiException;
import rw.bloomco.common.Db;
import rw.bloomco.common.Json;
import rw.bloomco.common.Row;
import rw.bloomco.order.Pricing;
import rw.bloomco.settings.SettingsService;

/** Shopping cart: server-side carts for customers, priced quotes for guest (browser) carts. */
@Service
public class CartService {

    public record Item(int productId, int quantity) {}

    private final Db db;
    private final SettingsService settings;

    public CartService(Db db, SettingsService settings) {
        this.db = db;
        this.settings = settings;
    }

    private int cartId(int userId) {
        return db.insertId("""
                INSERT INTO cart (user_id) VALUES (?)
                ON CONFLICT (user_id) DO UPDATE SET user_id = EXCLUDED.user_id RETURNING id""", userId);
    }

    private static Map<String, Object> toLine(Row r, int quantity, boolean saved) {
        long price = r.money("price");
        int discount = r.intOr0("discount_percent");
        long unit = Pricing.unitPrice(price, discount);
        int stock = r.intOr0("stock");
        return Json.obj(
                "productId", r.integer("product_id"),
                "name", r.str("name"),
                "slug", r.str("slug"),
                "imageUrl", r.str("image_url"),
                "price", price,
                "discountPercent", discount,
                "unitPrice", unit,
                "quantity", quantity,
                "stock", stock,
                "available", "active".equals(r.str("status")) && stock > 0,
                "savedForLater", saved,
                "lineTotal", unit * quantity);
    }

    private Map<String, Object> summarize(List<Map<String, Object>> lines) {
        var delivery = settings.delivery();
        List<Pricing.Line> active = lines.stream()
                .filter(l -> !(Boolean) l.get("savedForLater") && (Boolean) l.get("available"))
                .map(l -> new Pricing.Line((Long) l.get("price"), (Integer) l.get("discountPercent"), (Integer) l.get("quantity")))
                .toList();
        Map<String, Object> summary = new LinkedHashMap<>(Pricing.compute(active, delivery).toJson());
        summary.put("freeDeliveryThreshold", delivery.freeDeliveryThreshold());
        return Json.obj(
                "items", lines.stream().filter(l -> !(Boolean) l.get("savedForLater")).toList(),
                "saved", lines.stream().filter(l -> (Boolean) l.get("savedForLater")).toList(),
                "summary", summary);
    }

    public Map<String, Object> get(int userId) {
        int id = cartId(userId);
        var lines = db.rows("""
                SELECT ci.product_id, ci.quantity, ci.saved_for_later, p.name, p.slug, p.image_url, p.price,
                       p.discount_percent, p.stock, p.status
                FROM cart_items ci JOIN products p ON p.id = ci.product_id
                WHERE ci.cart_id = ? ORDER BY ci.id""", id).stream()
                .map(r -> toLine(r, r.intOr0("quantity"), Boolean.TRUE.equals(r.bool("saved_for_later"))))
                .toList();
        return summarize(lines);
    }

    /** Price a guest cart held in the browser (no persistence). */
    public Map<String, Object> quote(List<Item> items) {
        if (items.isEmpty()) return summarize(List.of());
        List<Integer> ids = items.stream().map(Item::productId).toList();
        Map<Integer, Row> byId = db.rows("""
                SELECT id AS product_id, name, slug, image_url, price, discount_percent, stock, status
                FROM products WHERE id = ANY(?)""", db.intArray(ids)).stream()
                .collect(Collectors.toMap(r -> r.integer("product_id"), r -> r, (a, b) -> a));
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Item i : items) {
            Row r = byId.get(i.productId());
            if (r != null) lines.add(toLine(r, i.quantity(), false));
        }
        return summarize(lines);
    }

    private void assertStock(int productId, int quantity) {
        Row p = db.one("SELECT name, stock, status FROM products WHERE id = ?", productId)
                .filter(r -> "active".equals(r.str("status")))
                .orElseThrow(() -> ApiException.notFound("This flower is no longer available"));
        if (quantity > p.intOr0("stock")) throw ApiException.badRequest("Only " + p.intOr0("stock") + " " + p.str("name") + " left in stock");
    }

    public Map<String, Object> add(int userId, int productId, int quantity) {
        int id = cartId(userId);
        int current = db.one("SELECT quantity FROM cart_items WHERE cart_id = ? AND product_id = ?", id, productId)
                .map(r -> r.intOr0("quantity")).orElse(0);
        int newQty = Math.min(99, current + quantity);
        assertStock(productId, newQty);
        db.update("""
                INSERT INTO cart_items (cart_id, product_id, quantity) VALUES (?,?,?)
                ON CONFLICT (cart_id, product_id) DO UPDATE SET quantity = EXCLUDED.quantity, saved_for_later = FALSE""",
                id, productId, newQty);
        return get(userId);
    }

    public Map<String, Object> update(int userId, int productId, Integer quantity, Boolean savedForLater) {
        int id = cartId(userId);
        if (quantity != null) assertStock(productId, quantity);
        int n = db.update("""
                UPDATE cart_items SET quantity = COALESCE(?, quantity), saved_for_later = COALESCE(?, saved_for_later)
                WHERE cart_id = ? AND product_id = ?""", quantity, savedForLater, id, productId);
        if (n == 0) throw ApiException.notFound("Item is not in your cart");
        return get(userId);
    }

    public Map<String, Object> remove(int userId, int productId) {
        int id = cartId(userId);
        db.update("DELETE FROM cart_items WHERE cart_id = ? AND product_id = ?", id, productId);
        return get(userId);
    }

    /** Merge a guest (browser) cart into the user's cart after login. */
    public Map<String, Object> merge(int userId, List<Item> items) {
        int id = cartId(userId);
        for (Item i : items) {
            db.update("""
                    INSERT INTO cart_items (cart_id, product_id, quantity)
                    SELECT ?, p.id, LEAST(?, p.stock, 99) FROM products p WHERE p.id = ? AND p.status = 'active' AND p.stock > 0
                    ON CONFLICT (cart_id, product_id)
                    DO UPDATE SET quantity = LEAST(cart_items.quantity + EXCLUDED.quantity, 99)""",
                    id, i.quantity(), i.productId());
        }
        return get(userId);
    }

    /** Called inside the checkout transaction. */
    public void clearPurchased(int userId) {
        db.update("""
                DELETE FROM cart_items WHERE saved_for_later = FALSE
                AND cart_id = (SELECT id FROM cart WHERE user_id = ?)""", userId);
    }
}
