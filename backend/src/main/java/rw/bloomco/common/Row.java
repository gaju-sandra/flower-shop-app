package rw.bloomco.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Typed accessors over a JDBC result row (Map from JdbcTemplate.queryForList).
 * Converts PostgreSQL types to the JSON-friendly values the API returns.
 */
public final class Row {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Map<String, Object> m;

    public Row(Map<String, Object> m) {
        this.m = m;
    }

    public static List<Row> of(List<Map<String, Object>> rows) {
        return rows.stream().map(Row::new).toList();
    }

    public boolean has(String k) {
        return m.containsKey(k);
    }

    public Object raw(String k) {
        return m.get(k);
    }

    public String str(String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    public Integer integer(String k) {
        Object v = m.get(k);
        return v == null ? null : ((Number) v).intValue();
    }

    public int intOr0(String k) {
        Integer v = integer(k);
        return v == null ? 0 : v;
    }

    public Long lng(String k) {
        Object v = m.get(k);
        return v == null ? null : ((Number) v).longValue();
    }

    public long longOr0(String k) {
        Long v = lng(k);
        return v == null ? 0 : v;
    }

    /** Money / NUMERIC: whole numbers come back as long, fractions as double. */
    public Number num(String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (v instanceof BigDecimal bd) {
            BigDecimal s = bd.stripTrailingZeros();
            return s.scale() <= 0 ? (Number) s.longValueExact() : (Number) bd.doubleValue();
        }
        return (Number) v;
    }

    public long money(String k) {
        Object v = m.get(k);
        if (v == null) return 0;
        if (v instanceof BigDecimal bd) return bd.setScale(0, RoundingMode.HALF_UP).longValue();
        return Math.round(((Number) v).doubleValue());
    }

    public double dbl(String k) {
        Object v = m.get(k);
        return v == null ? 0 : ((Number) v).doubleValue();
    }

    public Boolean bool(String k) {
        Object v = m.get(k);
        return v == null ? null : (Boolean) v;
    }

    /** DATE -> "YYYY-MM-DD" */
    public String date(String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    /** TIMESTAMPTZ -> Instant (serialised as ISO-8601) */
    public Instant ts(String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (v instanceof Timestamp t) return t.toInstant();
        if (v instanceof OffsetDateTime o) return o.toInstant();
        if (v instanceof Instant i) return i;
        return Instant.parse(v.toString());
    }

    /** TEXT[] -> List<String> */
    public List<String> strings(String k) {
        Object v = m.get(k);
        if (v == null) return List.of();
        try {
            if (v instanceof Array a) return Arrays.stream((Object[]) a.getArray()).map(String::valueOf).toList();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        if (v instanceof String[] arr) return List.of(arr);
        return List.of();
    }

    /** JSONB -> parsed Java value (Map / List). */
    public Object json(String k) {
        Object v = m.get(k);
        if (v == null) return null;
        String text = v.toString(); // PGobject.toString() returns the JSON text
        try {
            return MAPPER.readValue(text, Object.class);
        } catch (Exception e) {
            return null;
        }
    }
}
