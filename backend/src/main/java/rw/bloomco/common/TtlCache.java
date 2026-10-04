package rw.bloomco.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Small in-process TTL cache for hot, read-mostly queries (catalogue, settings).
 * Keys are grouped by prefix so a write can invalidate a whole group.
 */
@Component
public class TtlCache {

    private record Entry(Object value, long expires) {}

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T get(String key, long ttlMillis, Supplier<T> loader) {
        if (ttlMillis <= 0) return loader.get();
        Entry hit = store.get(key);
        if (hit != null && hit.expires > System.currentTimeMillis()) return (T) hit.value;
        T value = loader.get();
        if (store.size() > 1000) store.clear();
        store.put(key, new Entry(value, System.currentTimeMillis() + ttlMillis));
        return value;
    }

    public void invalidate(String prefix) {
        store.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
