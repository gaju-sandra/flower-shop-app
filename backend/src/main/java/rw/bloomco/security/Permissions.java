package rw.bloomco.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Role-Based Access Control model.
 *
 * Every protected endpoint declares the <em>permission</em> it needs
 * ({@code @PreAuthorize("hasAuthority('orders:read:any')")}), never a role.
 * Roles are mapped to permissions here, in one place, so the policy can be
 * reviewed and unit-tested independently of the controllers.
 */
public final class Permissions {

    private Permissions() {}

    public static final String CUSTOMER = "customer";
    public static final String STAFF = "staff";
    public static final String ADMIN = "admin";

    // customer self-service
    public static final String CART_MANAGE = "cart:manage";
    public static final String WISHLIST_MANAGE = "wishlist:manage";
    public static final String ORDER_CREATE = "orders:create";
    public static final String ORDER_READ_OWN = "orders:read:own";
    public static final String REVIEW_CREATE = "reviews:create";
    public static final String ADDRESS_MANAGE = "addresses:manage";
    public static final String PROFILE_MANAGE = "profile:manage";

    // operations (staff + admin)
    public static final String ORDER_READ_ANY = "orders:read:any";
    public static final String ORDER_UPDATE_STATUS = "orders:update-status";
    public static final String DELIVERY_READ = "deliveries:read";
    public static final String DELIVERY_MANAGE = "deliveries:manage";
    public static final String CUSTOMER_READ = "customers:read";
    public static final String PRODUCT_READ_ADMIN = "products:read-admin";
    public static final String PRODUCT_UPDATE_STOCK = "products:update-stock";
    public static final String MESSAGE_READ = "messages:read";
    public static final String MESSAGE_REPLY = "messages:reply";
    public static final String STAFF_DASHBOARD = "dashboard:staff";

    // administration only
    public static final String PRODUCT_MANAGE = "products:manage";
    public static final String CATEGORY_MANAGE = "categories:manage";
    public static final String CUSTOMER_MANAGE = "customers:manage";
    public static final String STAFF_MANAGE = "staff:manage";
    public static final String PAYMENT_READ = "payments:read";
    public static final String PAYMENT_MANAGE = "payments:manage";
    public static final String PROMOTION_MANAGE = "promotions:manage";
    public static final String REPORT_READ = "reports:read";
    public static final String REVIEW_MODERATE = "reviews:moderate";
    public static final String SETTINGS_MANAGE = "settings:manage";
    public static final String AUDIT_READ = "audit:read";

    private static final List<String> CUSTOMER_PERMS = List.of(
            CART_MANAGE, WISHLIST_MANAGE, ORDER_CREATE, ORDER_READ_OWN, REVIEW_CREATE, ADDRESS_MANAGE, PROFILE_MANAGE);

    private static final List<String> STAFF_PERMS = List.of(
            PROFILE_MANAGE, ORDER_READ_ANY, ORDER_UPDATE_STATUS, DELIVERY_READ, DELIVERY_MANAGE, CUSTOMER_READ,
            PRODUCT_READ_ADMIN, PRODUCT_UPDATE_STOCK, MESSAGE_READ, MESSAGE_REPLY, STAFF_DASHBOARD);

    /** Administrators inherit every staff permission plus the administration set. */
    private static final List<String> ADMIN_PERMS = concat(STAFF_PERMS, List.of(
            PRODUCT_MANAGE, CATEGORY_MANAGE, CUSTOMER_MANAGE, STAFF_MANAGE, PAYMENT_READ, PAYMENT_MANAGE,
            PROMOTION_MANAGE, REPORT_READ, REVIEW_MODERATE, SETTINGS_MANAGE, AUDIT_READ));

    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            CUSTOMER, Set.copyOf(CUSTOMER_PERMS),
            STAFF, Set.copyOf(STAFF_PERMS),
            ADMIN, Set.copyOf(ADMIN_PERMS));

    private static final Map<String, List<String>> ORDERED = Map.of(
            CUSTOMER, CUSTOMER_PERMS, STAFF, STAFF_PERMS, ADMIN, ADMIN_PERMS);

    public static boolean has(String role, String permission) {
        return ROLE_PERMISSIONS.getOrDefault(role, Set.of()).contains(permission);
    }

    public static List<String> forRole(String role) {
        return ORDERED.getOrDefault(role, List.of());
    }

    private static List<String> concat(List<String> a, List<String> b) {
        var set = new LinkedHashSet<>(a);
        set.addAll(b);
        return List.copyOf(set);
    }
}
