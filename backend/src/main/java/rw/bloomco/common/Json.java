package rw.bloomco.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds ordered JSON objects: {@code Json.obj("id", 1, "name", "Roses")}. */
public final class Json {

    private Json() {}

    public static Map<String, Object> obj(Object... keyValues) {
        if (keyValues.length % 2 != 0) throw new IllegalArgumentException("obj() needs key/value pairs");
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) map.put((String) keyValues[i], keyValues[i + 1]);
        return map;
    }

    /** Copy of {@code base} with extra entries appended. */
    public static Map<String, Object> with(Map<String, Object> base, Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>(base);
        map.putAll(obj(keyValues));
        return map;
    }
}
