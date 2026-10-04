package rw.bloomco.common;

import java.util.List;
import java.util.Map;

/** ?page / ?limit parsing and the standard paginated response shape. */
public record Paging(int page, int limit, int offset) {

    public static Paging of(Integer page, Integer limit, int defaultLimit, int maxLimit) {
        int p = Math.max(1, page == null ? 1 : page);
        int l = Math.min(maxLimit, Math.max(1, limit == null ? defaultLimit : limit));
        return new Paging(p, l, (p - 1) * l);
    }

    public Map<String, Object> result(List<?> items, long total) {
        return Json.obj("items", items, "total", total, "page", page, "pages", Math.max(1, (int) Math.ceil(total / (double) limit)));
    }
}
