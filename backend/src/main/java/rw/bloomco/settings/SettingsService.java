package rw.bloomco.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import rw.bloomco.common.Db;
import rw.bloomco.common.Json;
import rw.bloomco.common.Row;
import rw.bloomco.common.TtlCache;

/** Store-wide settings (JSONB rows keyed by section) and the gift-option catalogue. */
@Service
public class SettingsService {

    public static final Map<String, Map<String, Object>> DEFAULTS = Map.of(
            "store", Json.obj(
                    "name", "Bloom & Co.",
                    "phone", "+250 788 123 456",
                    "email", "hello@bloomandco.rw",
                    "address", "KG 7 Ave, Kacyiru, Kigali, Rwanda",
                    "hours", "Mon – Sat: 8:00 – 20:00 · Sun: 9:00 – 17:00"),
            "delivery", Json.obj(
                    "deliveryFee", 2000,
                    "freeDeliveryThreshold", 50000,
                    "timeSlots", List.of("08:00 - 10:00", "10:00 - 12:00", "12:00 - 14:00", "14:00 - 16:00", "16:00 - 18:00", "18:00 - 20:00"),
                    "sameDayCutoffHour", 14),
            "social", Json.obj(
                    "instagram", "https://instagram.com",
                    "facebook", "https://facebook.com",
                    "x", "https://x.com",
                    "whatsapp", "https://wa.me/250788123456"));

    /** Typed view of the delivery section used by pricing and checkout. */
    public record Delivery(long deliveryFee, long freeDeliveryThreshold, List<String> timeSlots, int sameDayCutoffHour) {}

    private final Db db;
    private final TtlCache cache;
    private final ObjectMapper mapper;

    public SettingsService(Db db, TtlCache cache, ObjectMapper mapper) {
        this.db = db;
        this.cache = cache;
        this.mapper = mapper;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getSettings() {
        return cache.get("settings:all", 60_000, () -> {
            Map<String, Object> merged = new LinkedHashMap<>();
            for (String section : List.of("store", "delivery", "social")) merged.put(section, new LinkedHashMap<>(DEFAULTS.get(section)));
            for (Row r : db.rows("SELECT key, value FROM settings")) {
                Object value = r.json("value");
                if (value instanceof Map<?, ?> m) {
                    Map<String, Object> target = (Map<String, Object>) merged.computeIfAbsent(r.str("key"), k -> new LinkedHashMap<>());
                    target.putAll((Map<String, Object>) m);
                }
            }
            return merged;
        });
    }

    @SuppressWarnings("unchecked")
    public Delivery delivery() {
        Map<String, Object> d = (Map<String, Object>) getSettings().get("delivery");
        return new Delivery(
                ((Number) d.get("deliveryFee")).longValue(),
                ((Number) d.get("freeDeliveryThreshold")).longValue(),
                (List<String>) d.get("timeSlots"),
                ((Number) d.get("sameDayCutoffHour")).intValue());
    }

    public Map<String, Object> update(String section, Map<String, Object> value) {
        try {
            db.update("""
                    INSERT INTO settings (key, value) VALUES (?, ?::jsonb)
                    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()""",
                    section, mapper.writeValueAsString(value));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        cache.invalidate("settings:");
        return getSettings();
    }

    public List<Map<String, Object>> giftOptions(boolean includeInactive) {
        return cache.get("settings:gifts:" + includeInactive, 60_000, () ->
                db.rows("SELECT * FROM gift_options " + (includeInactive ? "" : "WHERE active ") + "ORDER BY id").stream()
                        .map(g -> Json.obj("id", g.integer("id"), "code", g.str("code"), "name", g.str("name"),
                                "icon", g.str("icon"), "price", g.money("price"), "active", g.bool("active")))
                        .toList());
    }

    public void updateGiftOption(int id, Long price, Boolean active) {
        db.update("UPDATE gift_options SET price = COALESCE(?, price), active = COALESCE(?, active) WHERE id = ?", price, active, id);
        cache.invalidate("settings:");
    }

    /** Object -> JSON-style map (used to store a validated section). */
    public Map<String, Object> toMap(Object value) {
        return mapper.convertValue(value, new TypeReference<>() {});
    }

    /** JSON-style map -> typed section record; malformed input becomes a 400. */
    public <T> T toMapAs(Map<String, Object> body, Class<T> type) {
        try {
            return mapper.convertValue(body, type);
        } catch (IllegalArgumentException e) {
            throw rw.bloomco.common.ApiException.badRequest("Invalid settings values");
        }
    }
}
