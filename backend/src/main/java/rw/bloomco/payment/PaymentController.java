package rw.bloomco.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import java.util.Set;
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
@RequestMapping("/api/payments")
public class PaymentController {

    public record StatusBody(@NotNull @Pattern(regexp = "paid|refunded|failed") String status) {}

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('payments:read')")
    public Map<String, Object> list(@RequestParam(required = false) String status, @RequestParam(required = false) String method,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer limit) {
        return payments.list(Text.oneOf(status, Set.of("pending", "paid", "failed", "refunded"), "status"),
                Text.oneOf(method, PaymentService.METHOD_LABELS.keySet(), "method"), Paging.of(page, limit, 20, 100));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('payments:manage')")
    public Map<String, Object> setStatus(@PathVariable int id, @Valid @RequestBody StatusBody body, @AuthenticationPrincipal AuthUser user) {
        return payments.setStatus(id, body.status(), user.id());
    }
}
