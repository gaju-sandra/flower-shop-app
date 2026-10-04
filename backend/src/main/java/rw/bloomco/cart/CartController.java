package rw.bloomco.cart;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.bloomco.common.ApiException;
import rw.bloomco.security.AuthUser;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    public record Line(@NotNull @Min(1) Integer productId, @Min(1) @Max(99) Integer quantity) {
        CartService.Item toItem() {
            return new CartService.Item(productId, quantity == null ? 1 : quantity);
        }
    }

    public record Lines(@NotNull @Size(max = 50) List<@Valid Line> items) {
        List<CartService.Item> toItems() {
            return items.stream().map(Line::toItem).toList();
        }
    }

    public record LinePatch(@Min(1) @Max(99) Integer quantity, Boolean savedForLater) {}

    private final CartService cart;

    public CartController(CartService cart) {
        this.cart = cart;
    }

    /** Guest carts live in the browser; this endpoint prices them with live data. */
    @PostMapping("/quote")
    public Map<String, Object> quote(@Valid @RequestBody Lines body) {
        return cart.quote(body.toItems());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('cart:manage')")
    public Map<String, Object> get(@AuthenticationPrincipal AuthUser user) {
        return cart.get(user.id());
    }

    @PostMapping("/items")
    @PreAuthorize("hasAuthority('cart:manage')")
    public Map<String, Object> add(@Valid @RequestBody Line body, @AuthenticationPrincipal AuthUser user) {
        var item = body.toItem();
        return cart.add(user.id(), item.productId(), item.quantity());
    }

    @PatchMapping("/items/{productId}")
    @PreAuthorize("hasAuthority('cart:manage')")
    public Map<String, Object> update(@PathVariable int productId, @Valid @RequestBody LinePatch body,
            @AuthenticationPrincipal AuthUser user) {
        if (body.quantity() == null && body.savedForLater() == null) throw ApiException.badRequest("Nothing to update");
        return cart.update(user.id(), productId, body.quantity(), body.savedForLater());
    }

    @DeleteMapping("/items/{productId}")
    @PreAuthorize("hasAuthority('cart:manage')")
    public Map<String, Object> remove(@PathVariable int productId, @AuthenticationPrincipal AuthUser user) {
        return cart.remove(user.id(), productId);
    }

    @PostMapping("/merge")
    @PreAuthorize("hasAuthority('cart:manage')")
    public Map<String, Object> merge(@Valid @RequestBody Lines body, @AuthenticationPrincipal AuthUser user) {
        return cart.merge(user.id(), body.toItems());
    }
}
