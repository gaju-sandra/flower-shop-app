package rw.bloomco.common;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** String helpers shared across modules. */
public final class Text {

    private Text() {}

    public static final Pattern RW_PHONE = Pattern.compile("^(\\+?250|0)7[2389]\\d{7}$");
    public static final String RW_PHONE_REGEX = "^(\\+?250|0)7[2389]\\d{7}$";
    public static final String PHONE_MESSAGE = "Enter a valid Rwandan phone number, e.g. 0788123456";
    public static final String DATE_REGEX = "^\\d{4}-\\d{2}-\\d{2}$";

    public static String slugify(String s) {
        String n = Normalizer.normalize(String.valueOf(s), Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        n = n.replaceAll("[^\\w\\s-]", "").trim().replaceAll("[\\s_-]+", "-");
        return n.length() > 120 ? n.substring(0, 120) : n;
    }

    public static String cleanPhone(String s) {
        return s == null ? null : s.replaceAll("[\\s-]", "");
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    public static String trim(String s) {
        return s == null ? null : s.trim();
    }

    /** Throws 400 unless value is null or one of the allowed values. */
    public static String oneOf(String value, Set<String> allowed, String field) {
        if (value == null || value.isEmpty()) return null;
        if (!allowed.contains(value)) throw ApiException.badRequest("Invalid " + field + ": " + value);
        return value;
    }

    public static String requireDate(String value, String field) {
        if (value == null || value.isEmpty()) return null;
        if (!value.matches(DATE_REGEX)) throw ApiException.badRequest("Invalid " + field + " (expected YYYY-MM-DD)");
        return value;
    }

    public static String money(Number n) {
        return "RWF " + String.format(Locale.US, "%,d", Math.round(n == null ? 0 : n.doubleValue()));
    }
}
