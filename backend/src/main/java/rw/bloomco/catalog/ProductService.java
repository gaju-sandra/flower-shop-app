package rw.bloomco.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import rw.bloomco.common.ApiException;
import rw.bloomco.common.Db;
import rw.bloomco.common.Json;
import rw.bloomco.common.Paging;
import rw.bloomco.common.Row;
import rw.bloomco.common.Text;
import rw.bloomco.common.TtlCache;
import rw.bloomco.messaging.EventBus;

/** Flower catalogue: search / filter / sort, admin CRUD and categories. */
@Service
public class ProductService {

    private static final String EFFECTIVE_PRICE = "ROUND(p.price * (100 - p.discount_percent) / 100)";
    private static final Map<String, String> SORTS = Map.of(
            "newest", "p.created_at DESC",
            "popular", "p.sold_count DESC, p.rating_avg DESC",
            "rating", "p.rating_avg DESC, p.rating_count DESC",
            "price_asc", EFFECTIVE_PRICE + " ASC",
            "price_desc", EFFECTIVE_PRICE + " DESC",
            "name", "p.name ASC");
    private static final String BASE_SELECT = """
            SELECT p.*, c.name AS category_name, c.slug AS category_slug
            FROM products p LEFT JOIN categories c ON c.id = p.category_id""";

    private final Db db;
    private final TtlCache cache;
    private final EventBus events;

    public ProductService(Db db, TtlCache cache, EventBus events) {
        this.db = db;
        this.cache = cache;
        this.events = events;
    }

    public static long finalPrice(Number price, Number discount) {
        return Math.round(price.doubleValue() * (1 - (discount == null ? 0 : discount.doubleValue()) / 100));
    }

    public static Map<String, Object> toProduct(Row r) {
        return Json.obj(
                "id", r.integer("id"),
                "name", r.str("name"),
                "slug", r.str("slug"),
                "description", r.str("description"),
                "categoryId", r.integer("category_id"),
                "category", r.has("category_name") ? r.str("category_name") : null,
                "categorySlug", r.has("category_slug") ? r.str("category_slug") : null,
                "occasions", r.strings("occasions"),
                "price", r.money("price"),
                "discountPercent", r.intOr0("discount_percent"),
                "finalPrice", finalPrice(r.money("price"), r.intOr0("discount_percent")),
                "stock", r.intOr0("stock"),
                "imageUrl", r.str("image_url"),
                "status", r.str("status"),
                "soldCount", r.intOr0("sold_count"),
                "rating", r.dbl("rating_avg"),
                "ratingCount", r.intOr0("rating_count"),
                "createdAt", r.ts("created_at"));
    }

    public record Filter(String search, String category, String occasion, Double minPrice, Double maxPrice, String sort,
            boolean onSale, boolean lowStock, boolean includeInactive) {}

    public Map<String, Object> list(Filter f, Paging paging) {
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (!f.includeInactive()) where.add("p.status = 'active'");
        if (!Text.isBlank(f.search())) {
            where.add("(p.name ILIKE ? OR p.description ILIKE ?)");
            params.add("%" + f.search().trim() + "%");
            params.add("%" + f.search().trim() + "%");
        }
        if (!Text.isBlank(f.category())) { where.add("c.slug = ?"); params.add(f.category()); }
        if (!Text.isBlank(f.occasion())) { where.add("? = ANY(p.occasions)"); params.add(f.occasion()); }
        if (f.minPrice() != null) { where.add(EFFECTIVE_PRICE + " >= ?"); params.add(f.minPrice()); }
        if (f.maxPrice() != null) { where.add(EFFECTIVE_PRICE + " <= ?"); params.add(f.maxPrice()); }
        if (f.onSale()) where.add("p.discount_percent > 0");
        if (f.lowStock()) where.add("p.stock <= 5");

        String whereSql = where.isEmpty() ? "" : "WHERE " + String.join(" AND ", where);
        String order = SORTS.getOrDefault(f.sort() == null ? "popular" : f.sort(), SORTS.get("popular"));
        String key = "products:" + f + ":" + paging;
        Object[] args = params.toArray();
        return cache.get(key, f.includeInactive() ? 0 : 30_000, () -> {
            var items = db.rows(BASE_SELECT + " " + whereSql + " ORDER BY " + order + ", p.id LIMIT " + paging.limit()
                    + " OFFSET " + paging.offset(), args).stream().map(ProductService::toProduct).toList();
            long total = db.count("SELECT COUNT(*) FROM products p LEFT JOIN categories c ON c.id = p.category_id " + whereSql, args);
            return paging.result(items, total);
        });
    }

    public Map<String, Object> get(String idOrSlug, boolean includeInactive) {
        boolean byId = idOrSlug.matches("^\\d+$");
        Object key = byId ? (Object) Integer.parseInt(idOrSlug) : idOrSlug;
        return db.one(BASE_SELECT + " WHERE " + (byId ? "p.id = ?" : "p.slug = ?")
                        + (includeInactive ? "" : " AND p.status = 'active'"), key)
                .map(ProductService::toProduct)
                .orElseThrow(() -> ApiException.notFound("Flower not found"));
    }

    public Map<String, Object> get(int id) {
        return get(String.valueOf(id), true);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> related(Map<String, Object> product) {
        return db.rows(BASE_SELECT + "\n" + """
                 WHERE p.status = 'active' AND p.id <> ? AND (p.category_id = ? OR p.occasions && ?)
                 ORDER BY p.sold_count DESC LIMIT 4""",
                product.get("id"), product.get("categoryId"), db.textArray((List<String>) product.get("occasions")))
                .stream().map(ProductService::toProduct).toList();
    }

    private String uniqueSlug(String name, int excludeId) {
        String base = Text.slugify(name);
        if (base.isEmpty()) base = "flower";
        String slug = base;
        for (int i = 2; db.count("SELECT COUNT(*) FROM products WHERE slug = ? AND id <> ?", slug, excludeId) > 0; i++) {
            slug = base + "-" + i;
        }
        return slug;
    }

    public Map<String, Object> create(ProductController.ProductBody d, String imageUrl, int actorId) {
        if (Text.isBlank(d.name()) || d.price() == null || d.stock() == null) {
            throw ApiException.badRequest("Name, price and stock are required");
        }
        int id = db.insertId("""
                INSERT INTO products (name, slug, description, category_id, occasions, price, discount_percent, stock, image_url, status)
                VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id""",
                d.name().trim(), uniqueSlug(d.name(), 0), d.description() == null ? "" : d.description().trim(), d.categoryId(),
                db.textArray(d.occasionList()), d.price(), d.discountPercent() == null ? 0 : d.discountPercent(), d.stock(),
                imageUrl, d.status() == null ? "active" : d.status());
        cache.invalidate("products:");
        events.publish("product.created", Json.obj("actorId", actorId, "entity", "product", "entityId", id, "name", d.name()));
        return get(id);
    }

    public Map<String, Object> update(int id, ProductController.ProductBody d, String imageUrl, int actorId) {
        Map<String, Object> sets = new LinkedHashMap<>();
        if (d.name() != null) {
            sets.put("name", d.name().trim());
            sets.put("slug", uniqueSlug(d.name(), id));
        }
        if (d.description() != null) sets.put("description", d.description().trim());
        if (d.categoryIdSet()) sets.put("category_id", d.categoryId());
        if (d.occasions() != null) sets.put("occasions", db.textArray(d.occasionList()));
        if (d.price() != null) sets.put("price", d.price());
        if (d.discountPercent() != null) sets.put("discount_percent", d.discountPercent());
        if (d.stock() != null) sets.put("stock", d.stock());
        if (imageUrl != null) sets.put("image_url", imageUrl.isBlank() ? null : imageUrl);
        if (d.status() != null) sets.put("status", d.status());
        return applyUpdate(id, sets, actorId);
    }

    public Map<String, Object> updateStock(int id, int stock, int actorId) {
        return applyUpdate(id, Map.of("stock", stock), actorId);
    }

    private Map<String, Object> applyUpdate(int id, Map<String, Object> sets, int actorId) {
        if (sets.isEmpty()) return get(id);
        String sql = "UPDATE products SET " + String.join(", ", sets.keySet().stream().map(c -> c + " = ?").toList())
                + ", updated_at = NOW() WHERE id = ?";
        List<Object> params = new ArrayList<>(sets.values());
        params.add(id);
        if (db.update(sql, params.toArray()) == 0) throw ApiException.notFound("Flower not found");
        cache.invalidate("products:");
        events.publish("product.updated", Json.obj("actorId", actorId, "entity", "product", "entityId", id,
                "changes", List.copyOf(sets.keySet())));
        return get(id);
    }

    public void delete(int id, int actorId) {
        if (db.update("DELETE FROM products WHERE id = ?", id) == 0) throw ApiException.notFound("Flower not found");
        cache.invalidate("products:");
        events.publish("product.deleted", Json.obj("actorId", actorId, "entity", "product", "entityId", id));
    }

    // ------------------------------------------------------------------ categories

    public List<Map<String, Object>> categories() {
        return cache.get("products:categories", 60_000, () -> db.rows("""
                SELECT c.*, COUNT(p.id) FILTER (WHERE p.status = 'active') AS product_count
                FROM categories c LEFT JOIN products p ON p.category_id = c.id
                GROUP BY c.id ORDER BY c.name""").stream()
                .map(c -> Json.obj("id", c.integer("id"), "name", c.str("name"), "slug", c.str("slug"),
                        "description", c.str("description"), "productCount", c.longOr0("product_count")))
                .toList());
    }

    private static Map<String, Object> toCategory(Row c) {
        return Json.obj("id", c.integer("id"), "name", c.str("name"), "slug", c.str("slug"), "description", c.str("description"));
    }

    public Map<String, Object> createCategory(String name, String description) {
        var row = db.rows("INSERT INTO categories (name, slug, description) VALUES (?,?,?) RETURNING *",
                name.trim(), Text.slugify(name), Text.blankToNull(description)).get(0);
        cache.invalidate("products:");
        return toCategory(row);
    }

    public Map<String, Object> updateCategory(int id, String name, String description) {
        var rows = db.rows("UPDATE categories SET name = ?, slug = ?, description = ? WHERE id = ? RETURNING *",
                name.trim(), Text.slugify(name), Text.blankToNull(description), id);
        if (rows.isEmpty()) throw ApiException.notFound("Category not found");
        cache.invalidate("products:");
        return toCategory(rows.get(0));
    }

    public void deleteCategory(int id) {
        if (db.count("SELECT COUNT(*) FROM products WHERE category_id = ?", id) > 0) {
            throw ApiException.conflict("Move or delete the flowers in this category first");
        }
        db.update("DELETE FROM categories WHERE id = ?", id);
        cache.invalidate("products:");
    }
}
