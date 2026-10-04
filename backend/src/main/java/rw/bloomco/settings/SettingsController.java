package rw.bloomco.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.bloomco.common.ApiException;
import rw.bloomco.common.Json;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final String URL_OR_EMPTY = "^$|^https?://.+";

    public record StoreSection(
            @NotBlank @Size(min = 2, max = 60) String name,
            @NotBlank @Size(min = 6, max = 30) String phone,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 5, max = 200) String address,
            @NotNull @Size(max = 120) String hours) {}

    public record DeliverySection(
            @NotNull @Min(0) @Max(100_000) Long deliveryFee,
            @NotNull @Min(0) @Max(10_000_000) Long freeDeliveryThreshold,
            @NotNull @Size(min = 1, max = 12) List<@NotBlank @Size(min = 3, max = 20) String> timeSlots,
            @NotNull @Min(0) @Max(23) Integer sameDayCutoffHour) {}

    public record SocialSection(
            @NotNull @Pattern(regexp = URL_OR_EMPTY, message = "Enter a full URL (https://...)") String instagram,
            @NotNull @Pattern(regexp = URL_OR_EMPTY, message = "Enter a full URL (https://...)") String facebook,
            @NotNull @Pattern(regexp = URL_OR_EMPTY, message = "Enter a full URL (https://...)") String x,
            @NotNull @Pattern(regexp = URL_OR_EMPTY, message = "Enter a full URL (https://...)") String whatsapp) {}

    public record GiftOptionPatch(@Min(0) @Max(1_000_000) Long price, Boolean active) {}

    private static final Map<String, Class<?>> SECTIONS = Map.of(
            "store", StoreSection.class, "delivery", DeliverySection.class, "social", SocialSection.class);

    private final SettingsService settings;
    private final Validator validator;

    public SettingsController(SettingsService settings, Validator validator) {
        this.settings = settings;
        this.validator = validator;
    }

    /** Public storefront config: contact info, delivery fee, time slots, gift options. */
    @GetMapping("/public")
    public ResponseEntity<Map<String, Object>> publicSettings() {
        var body = Json.with(settings.getSettings(), "giftOptions", settings.giftOptions(false));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic()).body(body);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('settings:manage')")
    public Map<String, Object> all() {
        return Json.with(settings.getSettings(), "giftOptions", settings.giftOptions(true));
    }

    @PutMapping("/{section}")
    @PreAuthorize("hasAuthority('settings:manage')")
    public Map<String, Object> update(@PathVariable String section, @RequestBody Map<String, Object> body) {
        Class<?> type = SECTIONS.get(section);
        if (type == null) throw ApiException.notFound("Unknown settings section");
        Object parsed = settings.toMapAs(body, type);
        var violations = validator.validate(parsed);
        if (!violations.isEmpty()) {
            var details = violations.stream().map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage())).toList();
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, details.get(0).get("message"), details);
        }
        return settings.update(section, settings.toMap(parsed));
    }

    @PatchMapping("/gift-options/{id}")
    @PreAuthorize("hasAuthority('settings:manage')")
    public List<Map<String, Object>> updateGift(@PathVariable int id, @Valid @RequestBody GiftOptionPatch body) {
        settings.updateGiftOption(id, body.price(), body.active());
        return settings.giftOptions(true);
    }
}
