package rw.bloomco.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import rw.bloomco.common.Paging;
import rw.bloomco.common.Text;
import rw.bloomco.common.Validation;
import rw.bloomco.payment.PaymentService;
import rw.bloomco.security.AuthUser;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Set<String> STATUS_FILTER = new HashSet<>(OrderService.FLOW.keySet());

    static {
        STATUS_FILTER.add("active");
    }

    // ------------------------------------------------------------------ checkout request

    public record Contact(
            @NotBlank(message = "Full name is required") @Size(min = 3, max = 120, message = "Full name is required") String fullName,
            @NotBlank(message = Validation.PHONE_MESSAGE) @Pattern(regexp = Validation.PHONE_REGEX, message = Validation.PHONE_MESSAGE) String phone,
            @NotBlank(message = Validation.EMAIL_MESSAGE) @Email(message = Validation.EMAIL_MESSAGE) String email) {}

    public record Delivery(
            @NotBlank(message = "Province is required") @Size(min = 2, max = 60) String province,
            @NotBlank(message = "District is required") @Size(min = 2, max = 60) String district,
            @NotBlank(message = "Sector is required") @Size(min = 2, max = 60) String sector,
            @NotBlank(message = "Street / house address is required") @Size(min = 3, max = 200) String street,
            @Size(max = 300) String locationDescription,
            @NotBlank(message = "Choose a delivery date") @Pattern(regexp = Text.DATE_REGEX, message = "Choose a delivery date") String date,
            @NotBlank(message = "Choose a delivery time") @Size(min = 3, message = "Choose a delivery time") String time) {}

    public record Message(
            @Size(max = 120) String recipientName,
            @Pattern(regexp = "birthday|romantic|congratulations|anniversary|wedding|thank_you|custom", message = "Unknown message type") String type,
            @Size(max = 500) String text) {}

    public record Payment(
            @NotBlank(message = "Choose a payment method") @Pattern(regexp = "mtn_momo|airtel_money|card|cash_on_delivery", message = "Choose a payment method") String method,
            String phone,
            @Size(max = 80) String cardName,
            @Size(max = 23) String cardNumber,
            @Size(max = 7) String expiry,
            @Size(max = 4) String cvc) {
        PaymentService.PaymentInput toInput() {
            return new PaymentService.PaymentInput(method, phone, cardName, cardNumber, expiry, cvc);
        }
    }

    public record CheckoutRequest(
            @NotNull(message = "Contact details are required") @Valid Contact contact,
            @NotNull(message = "Delivery details are required") @Valid Delivery delivery,
            @Size(max = 500) String instructions,
            @Valid Message message,
            @Size(max = 10) List<@Size(max = 30) String> giftOptions,
            @Size(max = 30) String promoCode,
            Boolean saveAddress,
            @NotNull(message = "Choose a payment method") @Valid Payment payment) {

        public List<String> giftOptions() {
            return giftOptions == null ? List.of() : giftOptions;
        }
    }

    public record QuoteRequest(@Size(max = 10) List<String> giftOptions, @Size(max = 30) String promoCode) {}

    public record StatusRequest(
            @NotBlank @Pattern(regexp = "pending|confirmed|preparing|ready|out_for_delivery|delivered|cancelled", message = "Unknown status") String status,
            @Size(max = 300) String note) {}

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    // ------------------------------------------------------------------ customer

    @PostMapping("/quote")
    @PreAuthorize("hasAuthority('orders:create')")
    public Map<String, Object> quote(@Valid @RequestBody QuoteRequest body, @AuthenticationPrincipal AuthUser user) {
        return orders.quote(user.id(), body.giftOptions(), body.promoCode());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('orders:create')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> checkout(@Valid @RequestBody CheckoutRequest body, @AuthenticationPrincipal AuthUser user) {
        return orders.create(user.id(), body);
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('orders:read:own')")
    public Map<String, Object> mine(@RequestParam(required = false) String status, @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal AuthUser user) {
        var filter = new OrderService.ListFilter(user.id(), Text.oneOf(status, STATUS_FILTER, "status"), search, null, null);
        return orders.list(filter, Paging.of(page, limit, 10, 50));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('orders:read:own')")
    public Map<String, Object> cancel(@PathVariable int id, @AuthenticationPrincipal AuthUser user) {
        return orders.cancelOwn(id, user);
    }

    // ------------------------------------------------------------------ staff / admin

    @GetMapping
    @PreAuthorize("hasAuthority('orders:read:any')")
    public Map<String, Object> all(@RequestParam(required = false) String status, @RequestParam(required = false) String search,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer limit) {
        var filter = new OrderService.ListFilter(null, Text.oneOf(status, STATUS_FILTER, "status"), search,
                Text.requireDate(from, "from"), Text.requireDate(to, "to"));
        return orders.list(filter, Paging.of(page, limit, 15, 100));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('orders:update-status')")
    public Map<String, Object> status(@PathVariable int id, @Valid @RequestBody StatusRequest body, @AuthenticationPrincipal AuthUser user) {
        return orders.updateStatus(id, body.status(), user, body.note());
    }

    /** Shared: the owner or staff/admin (object-level check inside the service). */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> one(@PathVariable int id, @AuthenticationPrincipal AuthUser user) {
        return orders.get(id, user);
    }
}
