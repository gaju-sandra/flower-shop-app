package rw.bloomco.delivery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.bloomco.common.Paging;
import rw.bloomco.common.Text;
import rw.bloomco.security.AuthUser;

@RestController
@RequestMapping("/api/deliveries")
public class DeliveryController {

    public record AssignBody(@NotNull(message = "Choose a staff member") @Positive Integer staffId) {}

    public record StatusBody(
            @NotBlank @Pattern(regexp = "picked_up|on_the_way|delivered", message = "Unknown delivery status") String status,
            @Size(max = 300) String notes) {}

    private final DeliveryService deliveries;

    public DeliveryController(DeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('deliveries:read')")
    public Map<String, Object> list(@RequestParam(required = false) String status, @RequestParam(required = false) String date,
            @RequestParam(required = false) String mine, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer limit, @AuthenticationPrincipal AuthUser user) {
        boolean onlyMine = "true".equals(mine) || "1".equals(mine);
        return deliveries.list(Text.oneOf(status, DeliveryService.FLOW.keySet(), "status"), Text.requireDate(date, "date"),
                onlyMine ? user.id() : null, Paging.of(page, limit, 20, 100));
    }

    @GetMapping("/couriers")
    @PreAuthorize("hasAuthority('deliveries:manage')")
    public List<Map<String, Object>> couriers() {
        return deliveries.couriers();
    }

    @PatchMapping("/{id}/assign")
    @PreAuthorize("hasAuthority('deliveries:manage')")
    public Map<String, Object> assign(@PathVariable int id, @Valid @RequestBody AssignBody body, @AuthenticationPrincipal AuthUser user) {
        return deliveries.assign(id, body.staffId(), user.id());
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('deliveries:manage')")
    public Map<String, Object> status(@PathVariable int id, @Valid @RequestBody StatusBody body, @AuthenticationPrincipal AuthUser user) {
        return deliveries.updateStatus(id, body.status(), user, body.notes());
    }
}
