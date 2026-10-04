package rw.bloomco.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import rw.bloomco.common.ApiException;
import rw.bloomco.common.Text;
import rw.bloomco.security.AuthUser;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    public record ReviewBody(
            @NotNull(message = "Choose a flower") Integer productId,
            @NotNull(message = "Choose a rating") @Min(value = 1, message = "Rating must be 1-5") @Max(value = 5, message = "Rating must be 1-5") Integer rating,
            @Size(max = 1000) String comment) {}

    public record StatusBody(@NotNull @Pattern(regexp = "visible|hidden") String status) {}

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/mine/reviewable")
    @PreAuthorize("hasAuthority('reviews:create')")
    public List<Map<String, Object>> reviewable(@AuthenticationPrincipal AuthUser user) {
        return reviews.reviewable(user.id());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('reviews:create')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody ReviewBody body, @AuthenticationPrincipal AuthUser user) {
        return reviews.upsert(user.id(), body.productId(), body.rating(), body.comment());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('reviews:moderate')")
    public List<Map<String, Object>> all(@RequestParam(required = false) String status, @RequestParam(required = false) Integer rating) {
        if (rating != null && (rating < 1 || rating > 5)) throw ApiException.badRequest("Rating must be 1-5");
        return reviews.listAll(Text.oneOf(status, Set.of("visible", "hidden"), "status"), rating);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('reviews:moderate')")
    public ResponseEntity<Void> moderate(@PathVariable int id, @Valid @RequestBody StatusBody body, @AuthenticationPrincipal AuthUser user) {
        reviews.setStatus(id, body.status(), user.id());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('reviews:moderate')")
    public ResponseEntity<Void> delete(@PathVariable int id, @AuthenticationPrincipal AuthUser user) {
        reviews.delete(id, user.id());
        return ResponseEntity.noContent().build();
    }
}
