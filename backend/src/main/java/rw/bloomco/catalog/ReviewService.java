package rw.bloomco.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bloomco.common.ApiException;
import rw.bloomco.common.Db;
import rw.bloomco.common.Json;
import rw.bloomco.common.Row;
import rw.bloomco.common.Text;
import rw.bloomco.common.TtlCache;
import rw.bloomco.messaging.EventBus;

/** Ratings & reviews. A customer may review a flower only after an order containing it was delivered. */
@Service
public class ReviewService {

    private final Db db;
    private final TtlCache cache;
    private final EventBus events;

    public ReviewService(Db db, TtlCache cache, EventBus events) {
        this.db = db;
        this.cache = cache;
        this.events = events;
    }

    static Map<String, Object> toReview(Row r) {
        String first = r.has("first_name") ? r.str("first_name") : null;
        String last = r.has("last_name") ? r.str("last_name") : null;
        return Json.obj(
                "id", r.integer("id"),
                "productId", r.integer("product_id"),
                "productName", r.has("product_name") ? r.str("product_name") : null,
                "userId", r.integer("user_id"),
                "author", first == null ? null : first + " " + (last == null || last.isEmpty() ? "" : last.charAt(0)) + ".",
                "rating", r.integer("rating"),
                "comment", r.str("comment"),
                "status", r.str("status"),
                "createdAt", r.ts("created_at"));
    }

    private void refreshProductRating(int productId) {
        db.update("""
                UPDATE products p SET
                  rating_avg   = COALESCE((SELECT ROUND(AVG(rating)::numeric, 2) FROM reviews WHERE product_id = ? AND status = 'visible'), 0),
                  rating_count = (SELECT COUNT(*) FROM reviews WHERE product_id = ? AND status = 'visible')
                WHERE p.id = ?""", productId, productId, productId);
        cache.invalidate("products:");
    }

    public List<Map<String, Object>> forProduct(int productId) {
        return db.rows("""
                SELECT r.*, u.first_name, u.last_name FROM reviews r JOIN users u ON u.id = r.user_id
                WHERE r.product_id = ? AND r.status = 'visible' ORDER BY r.created_at DESC LIMIT 20""", productId)
                .stream().map(ReviewService::toReview).toList();
    }

    /** Products the customer received (delivered orders) and whether they reviewed them. */
    public List<Map<String, Object>> reviewable(int userId) {
        return db.rows("""
                SELECT DISTINCT ON (p.id) p.id, p.name, p.image_url, r.id AS review_id, r.rating
                FROM orders o
                JOIN order_items oi ON oi.order_id = o.id
                JOIN products p ON p.id = oi.product_id
                LEFT JOIN reviews r ON r.product_id = p.id AND r.user_id = o.user_id
                WHERE o.user_id = ? AND o.status = 'delivered'
                ORDER BY p.id""", userId).stream()
                .map(r -> Json.obj("productId", r.integer("id"), "name", r.str("name"), "imageUrl", r.str("image_url"),
                        "reviewed", r.integer("review_id") != null, "rating", r.integer("rating")))
                .toList();
    }

    /** Re-submitting edits the existing review. */
    @Transactional
    public Map<String, Object> upsert(int userId, int productId, int rating, String comment) {
        long eligible = db.count("""
                SELECT COUNT(*) FROM orders o JOIN order_items oi ON oi.order_id = o.id
                WHERE o.user_id = ? AND oi.product_id = ? AND o.status = 'delivered'""", userId, productId);
        if (eligible == 0) throw ApiException.forbidden("You can review a flower after your order containing it is delivered");
        Row review = db.rows("""
                INSERT INTO reviews (user_id, product_id, rating, comment) VALUES (?,?,?,?)
                ON CONFLICT (user_id, product_id) DO UPDATE SET rating = EXCLUDED.rating, comment = EXCLUDED.comment, created_at = NOW()
                RETURNING *""", userId, productId, rating, Text.blankToNull(comment)).get(0);
        refreshProductRating(productId);
        events.publish("review.submitted", Json.obj("actorId", userId, "entity", "review", "entityId", review.integer("id"),
                "productId", productId, "rating", rating));
        return toReview(review);
    }

    public List<Map<String, Object>> listAll(String status, Integer rating) {
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (status != null) { where.add("r.status = ?"); params.add(status); }
        if (rating != null) { where.add("r.rating = ?"); params.add(rating); }
        return db.rows("""
                SELECT r.*, u.first_name, u.last_name, p.name AS product_name
                FROM reviews r JOIN users u ON u.id = r.user_id JOIN products p ON p.id = r.product_id
                """ + (where.isEmpty() ? "" : "WHERE " + String.join(" AND ", where)) + " ORDER BY r.created_at DESC LIMIT 200",
                params.toArray()).stream().map(ReviewService::toReview).toList();
    }

    @Transactional
    public void setStatus(int id, String status, int actorId) {
        var rows = db.rows("UPDATE reviews SET status = ? WHERE id = ? RETURNING product_id", status, id);
        if (rows.isEmpty()) throw ApiException.notFound("Review not found");
        refreshProductRating(rows.get(0).integer("product_id"));
        events.publish("review.moderated", Json.obj("actorId", actorId, "entity", "review", "entityId", id, "status", status));
    }

    @Transactional
    public void delete(int id, int actorId) {
        var rows = db.rows("DELETE FROM reviews WHERE id = ? RETURNING product_id", id);
        if (rows.isEmpty()) throw ApiException.notFound("Review not found");
        refreshProductRating(rows.get(0).integer("product_id"));
        events.publish("review.deleted", Json.obj("actorId", actorId, "entity", "review", "entityId", id));
    }
}
