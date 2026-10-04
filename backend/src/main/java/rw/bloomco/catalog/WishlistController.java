package rw.bloomco.catalog;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import rw.bloomco.common.Db;
import rw.bloomco.security.AuthUser;

@RestController
@RequestMapping("/api/wishlist")
@PreAuthorize("hasAuthority('wishlist:manage')")
public class WishlistController {

    private final Db db;

    public WishlistController(Db db) {
        this.db = db;
    }

    private List<Map<String, Object>> list(int userId) {
        return db.rows("""
                SELECT p.*, c.name AS category_name, c.slug AS category_slug
                FROM wishlist w JOIN products p ON p.id = w.product_id LEFT JOIN categories c ON c.id = p.category_id
                WHERE w.user_id = ? ORDER BY w.created_at DESC""", userId)
                .stream().map(ProductService::toProduct).toList();
    }

    @GetMapping
    public List<Map<String, Object>> get(@AuthenticationPrincipal AuthUser user) {
        return list(user.id());
    }

    @PostMapping("/{productId}")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Map<String, Object>> add(@PathVariable int productId, @AuthenticationPrincipal AuthUser user) {
        db.update("INSERT INTO wishlist (user_id, product_id) VALUES (?,?) ON CONFLICT DO NOTHING", user.id(), productId);
        return list(user.id());
    }

    @DeleteMapping("/{productId}")
    public List<Map<String, Object>> remove(@PathVariable int productId, @AuthenticationPrincipal AuthUser user) {
        db.update("DELETE FROM wishlist WHERE user_id = ? AND product_id = ?", user.id(), productId);
        return list(user.id());
    }
}
