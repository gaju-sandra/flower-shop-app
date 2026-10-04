package rw.bloomco.promotion;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import rw.bloomco.common.ApiException;
import rw.bloomco.common.Db;
import rw.bloomco.common.Json;
import rw.bloomco.common.Row;
import rw.bloomco.common.Text;
import rw.bloomco.messaging.EventBus;
import rw.bloomco.security.AuthUser;

/** Promo codes and the "Special Offers" banners. */
@RestController
@RequestMapping("/api/promotions")
public class PromotionController {

    public record PromoBody(
            @NotBlank(message = "Code is required") @Pattern(regexp = "^[A-Za-z0-9_-]{3,30}$", message = "Code: 3-30 letters, numbers, - or _") String code,
            @NotBlank(message = "Title is required") @Size(min = 3, max = 120) String title,
            @Size(max = 300) String description,
            @NotNull(message = "Discount is required") @Min(value = 1, message = "Discount must be 1-90%") @Max(value = 90, message = "Discount must be 1-90%") Integer discountPercent,
            @Min(0) Long minOrder,
            Integer categoryId,
            @NotBlank(message = "Start date is required") @Pattern(regexp = Text.DATE_REGEX) String startsAt,
            String endsAt,
            Boolean active) {

        @AssertTrue(message = "End date must be after the start date")
        public boolean isEndAfterStart() {
            return endsAt == null || endsAt.isBlank() || startsAt == null || endsAt.compareTo(startsAt) >= 0;
        }

        String end() {
            return endsAt == null || endsAt.isBlank() ? null : Text.requireDate(endsAt, "end date");
        }
    }

    private final Db db;
    private final EventBus events;

    public PromotionController(Db db, EventBus events) {
        this.db = db;
        this.events = events;
    }

    static Map<String, Object> toPromo(Row p) {
        return Json.obj(
                "id", p.integer("id"), "code", p.str("code"), "title", p.str("title"), "description", p.str("description"),
                "discountPercent", p.intOr0("discount_percent"), "minOrder", p.money("min_order"), "categoryId", p.integer("category_id"),
                "category", p.has("category_name") ? p.str("category_name") : null, "startsAt", p.date("starts_at"),
                "endsAt", p.date("ends_at"), "active", p.bool("active"), "timesUsed", p.has("times_used") ? p.lng("times_used") : null);
    }

    /** Public: currently running offers. */
    @GetMapping("/active")
    public List<Map<String, Object>> active() {
        return db.rows("""
                SELECT p.*, c.name AS category_name FROM promotions p LEFT JOIN categories c ON c.id = p.category_id
                WHERE p.active AND p.starts_at <= CURRENT_DATE AND (p.ends_at IS NULL OR p.ends_at >= CURRENT_DATE)
                ORDER BY p.discount_percent DESC""").stream().map(PromotionController::toPromo).toList();
    }

    @GetMapping
    @PreAuthorize("hasAuthority('promotions:manage')")
    public List<Map<String, Object>> all() {
        return db.rows("""
                SELECT p.*, c.name AS category_name, (SELECT COUNT(*) FROM orders o WHERE o.promotion_id = p.id) AS times_used
                FROM promotions p LEFT JOIN categories c ON c.id = p.category_id ORDER BY p.created_at DESC""")
                .stream().map(PromotionController::toPromo).toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('promotions:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody PromoBody d, @AuthenticationPrincipal AuthUser user) {
        Row row = db.rows("""
                INSERT INTO promotions (code, title, description, discount_percent, min_order, category_id, starts_at, ends_at, active)
                VALUES (?,?,?,?,?,?,?::date,?::date,?) RETURNING *""",
                d.code().trim().toUpperCase(), d.title().trim(), Text.blankToNull(d.description()), d.discountPercent(),
                d.minOrder() == null ? 0 : d.minOrder(), d.categoryId(), d.startsAt(), d.end(), d.active() == null || d.active()).get(0);
        events.publish("promotion.created", Json.obj("actorId", user.id(), "entity", "promotion", "entityId", row.integer("id"), "code", row.str("code")));
        return toPromo(row);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('promotions:manage')")
    public Map<String, Object> update(@PathVariable int id, @Valid @RequestBody PromoBody d) {
        var rows = db.rows("""
                UPDATE promotions SET code=?, title=?, description=?, discount_percent=?, min_order=?, category_id=?,
                  starts_at=?::date, ends_at=?::date, active=? WHERE id=? RETURNING *""",
                d.code().trim().toUpperCase(), d.title().trim(), Text.blankToNull(d.description()), d.discountPercent(),
                d.minOrder() == null ? 0 : d.minOrder(), d.categoryId(), d.startsAt(), d.end(), d.active() == null || d.active(), id);
        if (rows.isEmpty()) throw ApiException.notFound("Promotion not found");
        return toPromo(rows.get(0));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('promotions:manage')")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        db.update("DELETE FROM promotions WHERE id = ?", id);
        return ResponseEntity.noContent().build();
    }
}
