package rw.bloomco.common;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Thin data-access helper over JdbcTemplate (positional "?" parameters).
 * Services stay close to SQL: the schema has CHECK constraints, partial indexes,
 * arrays and JSONB that are clearer as SQL than as ORM mappings.
 */
@Component
public class Db {

    private final JdbcTemplate jdbc;

    public Db(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> rows(String sql, Object... params) {
        return Row.of(jdbc.queryForList(sql, params));
    }

    public Optional<Row> one(String sql, Object... params) {
        List<Map<String, Object>> list = jdbc.queryForList(sql, params);
        return list.isEmpty() ? Optional.empty() : Optional.of(new Row(list.get(0)));
    }

    public long count(String sql, Object... params) {
        Number n = jdbc.queryForObject(sql, Number.class, params);
        return n == null ? 0 : n.longValue();
    }

    public int update(String sql, Object... params) {
        return jdbc.update(sql, params);
    }

    /** INSERT ... RETURNING id */
    public int insertId(String sql, Object... params) {
        Number n = jdbc.queryForObject(sql, Number.class, params);
        return n == null ? 0 : n.intValue();
    }

    /** Converts a Java string list to a PostgreSQL TEXT[] parameter. */
    public java.sql.Array textArray(List<String> values) {
        return jdbc.execute((ConnectionCallback<java.sql.Array>) (Connection c) ->
                c.createArrayOf("text", values == null ? new Object[0] : values.toArray()));
    }

    /** Converts a Java int list to a PostgreSQL INT[] parameter. */
    public java.sql.Array intArray(List<Integer> values) {
        return jdbc.execute((ConnectionCallback<java.sql.Array>) (Connection c) ->
                c.createArrayOf("int4", values == null ? new Object[0] : values.toArray()));
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }
}
