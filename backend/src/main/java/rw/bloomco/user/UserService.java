package rw.bloomco.user;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bloomco.auth.AuthService;
import rw.bloomco.common.ApiException;
import rw.bloomco.common.Db;
import rw.bloomco.common.Json;
import rw.bloomco.common.Paging;
import rw.bloomco.common.Row;
import rw.bloomco.common.Text;
import rw.bloomco.messaging.EventBus;

/** Own profile, saved addresses, customer administration and staff management. */
@Service
public class UserService {

    private final Db db;
    private final PasswordEncoder encoder;
    private final EventBus events;

    public UserService(Db db, PasswordEncoder encoder, EventBus events) {
        this.db = db;
        this.encoder = encoder;
        this.events = events;
    }

    private Row user(int id) {
        return db.one("SELECT * FROM users WHERE id = ?", id).orElseThrow(() -> ApiException.notFound("User not found"));
    }

    private void assertEmailFree(String email, int exceptId) {
        if (email != null && db.count("SELECT COUNT(*) FROM users WHERE LOWER(email) = LOWER(?) AND id <> ?", email, exceptId) > 0) {
            throw ApiException.conflict("Another account already uses this email");
        }
    }

    private static String lower(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    // ---------------------------------------------------------------- own profile

    public Map<String, Object> updateProfile(int userId, UserController.ProfileBody b) {
        assertEmailFree(lower(b.email()), userId);
        db.update("""
                UPDATE users SET first_name = COALESCE(?, first_name), last_name = COALESCE(?, last_name),
                  email = COALESCE(?, email), phone = COALESCE(?, phone), updated_at = NOW() WHERE id = ?""",
                Text.trim(b.firstName()), Text.trim(b.lastName()), lower(b.email()), Text.cleanPhone(b.phone()), userId);
        events.publish("user.profile_updated", Json.obj("actorId", userId, "entity", "user", "entityId", userId));
        return AuthService.toPublicUser(user(userId));
    }

    public Map<String, Object> setAvatar(int userId, String url) {
        db.update("UPDATE users SET avatar_url = ?, updated_at = NOW() WHERE id = ?", url, userId);
        return AuthService.toPublicUser(user(userId));
    }

    public void changePassword(int userId, String currentPassword, String newPassword) {
        String hash = user(userId).str("password_hash");
        if (hash != null && (currentPassword == null || !encoder.matches(currentPassword, hash))) {
            throw ApiException.badRequest("Your current password is incorrect");
        }
        db.update("UPDATE users SET password_hash = ?, updated_at = NOW() WHERE id = ?", encoder.encode(newPassword), userId);
        events.publish("user.password_changed", Json.obj("actorId", userId, "entity", "user", "entityId", userId));
    }

    public Map<String, Object> customerDashboard(int userId) {
        Row s = db.one("""
                SELECT
                  (SELECT COUNT(*) FROM orders WHERE user_id = ?) AS total_orders,
                  (SELECT COUNT(*) FROM orders WHERE user_id = ? AND status NOT IN ('delivered','cancelled')) AS pending_orders,
                  (SELECT COUNT(*) FROM orders WHERE user_id = ? AND status = 'delivered') AS delivered_orders,
                  (SELECT COALESCE(SUM(total_amount),0) FROM orders WHERE user_id = ? AND status <> 'cancelled') AS total_spent,
                  (SELECT COALESCE(SUM(ci.quantity),0) FROM cart_items ci JOIN cart c ON c.id = ci.cart_id
                     WHERE c.user_id = ? AND NOT ci.saved_for_later) AS cart_items,
                  (SELECT COUNT(*) FROM wishlist WHERE user_id = ?) AS wishlist_items""",
                userId, userId, userId, userId, userId, userId).orElseThrow();
        var recent = db.rows("""
                SELECT o.id, o.order_number, o.total_amount, o.status, o.delivery_date, o.created_at,
                  (SELECT string_agg(product_name, ', ' ORDER BY id) FROM order_items WHERE order_id = o.id) AS flowers,
                  (SELECT SUM(quantity) FROM order_items WHERE order_id = o.id) AS quantity
                FROM orders o WHERE o.user_id = ? ORDER BY o.created_at DESC LIMIT 5""", userId).stream()
                .map(o -> Json.obj("id", o.integer("id"), "orderNumber", o.str("order_number"), "flowers", o.str("flowers"),
                        "quantity", o.longOr0("quantity"), "total", o.money("total_amount"), "status", o.str("status"),
                        "deliveryDate", o.date("delivery_date"), "createdAt", o.ts("created_at")))
                .toList();
        return Json.obj(
                "stats", Json.obj("totalOrders", s.longOr0("total_orders"), "pendingOrders", s.longOr0("pending_orders"),
                        "deliveredOrders", s.longOr0("delivered_orders"), "totalSpent", s.money("total_spent"),
                        "cartItems", s.longOr0("cart_items"), "wishlistItems", s.longOr0("wishlist_items")),
                "recentOrders", recent);
    }

    // ---------------------------------------------------------------- addresses

    static Map<String, Object> toAddress(Row a) {
        return Json.obj("id", a.integer("id"), "label", a.str("label"), "recipientName", a.str("recipient_name"), "phone", a.str("phone"),
                "province", a.str("province"), "district", a.str("district"), "sector", a.str("sector"), "street", a.str("street"),
                "locationDescription", a.str("location_description"), "isDefault", a.bool("is_default"));
    }

    public List<Map<String, Object>> addresses(int userId) {
        return db.rows("SELECT * FROM addresses WHERE user_id = ? ORDER BY is_default DESC, id", userId)
                .stream().map(UserService::toAddress).toList();
    }

    @Transactional
    public List<Map<String, Object>> saveAddress(int userId, UserController.AddressBody d, Integer addressId) {
        boolean makeDefault = Boolean.TRUE.equals(d.isDefault())
                || db.count("SELECT COUNT(*) FROM addresses WHERE user_id = ?", userId) == 0;
        if (makeDefault) db.update("UPDATE addresses SET is_default = FALSE WHERE user_id = ?", userId);
        Object[] values = {Text.isBlank(d.label()) ? "Home" : d.label().trim(), Text.blankToNull(d.recipientName()),
                Text.blankToNull(Text.cleanPhone(d.phone())), d.province().trim(), d.district().trim(), d.sector().trim(),
                d.street().trim(), Text.blankToNull(d.locationDescription()), makeDefault};
        if (addressId != null) {
            List<Object> p = new ArrayList<>(List.of(values));
            p.add(addressId);
            p.add(userId);
            int n = db.update("""
                    UPDATE addresses SET label=?, recipient_name=?, phone=?, province=?, district=?, sector=?, street=?,
                      location_description=?, is_default = (is_default OR ?) WHERE id = ? AND user_id = ?""", p.toArray());
            if (n == 0) throw ApiException.notFound("Address not found");
        } else {
            List<Object> p = new ArrayList<>(List.of(values));
            p.add(userId);
            db.update("""
                    INSERT INTO addresses (label, recipient_name, phone, province, district, sector, street, location_description, is_default, user_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?)""", p.toArray());
        }
        // keep exactly one default even if the edited default was un-flagged
        if (db.count("SELECT COUNT(*) FROM addresses WHERE user_id = ? AND is_default", userId) == 0) {
            db.update("UPDATE addresses SET is_default = TRUE WHERE id = (SELECT id FROM addresses WHERE user_id = ? ORDER BY id LIMIT 1)", userId);
        }
        return addresses(userId);
    }

    @Transactional
    public List<Map<String, Object>> deleteAddress(int userId, int addressId) {
        var rows = db.rows("DELETE FROM addresses WHERE id = ? AND user_id = ? RETURNING is_default", addressId, userId);
        if (rows.isEmpty()) throw ApiException.notFound("Address not found");
        if (Boolean.TRUE.equals(rows.get(0).bool("is_default"))) {
            db.update("UPDATE addresses SET is_default = TRUE WHERE id = (SELECT id FROM addresses WHERE user_id = ? ORDER BY id LIMIT 1)", userId);
        }
        return addresses(userId);
    }

    @Transactional
    public List<Map<String, Object>> setDefaultAddress(int userId, int addressId) {
        if (db.count("SELECT COUNT(*) FROM addresses WHERE id = ? AND user_id = ?", addressId, userId) == 0) {
            throw ApiException.notFound("Address not found");
        }
        db.update("UPDATE addresses SET is_default = FALSE WHERE user_id = ?", userId);
        db.update("UPDATE addresses SET is_default = TRUE WHERE id = ? AND user_id = ?", addressId, userId);
        return addresses(userId);
    }

    // ---------------------------------------------------------------- back office

    public Map<String, Object> list(String role, String search, String status, Paging paging) {
        List<String> where = new ArrayList<>(List.of("staff".equals(role) ? "u.role = 'staff'" : "u.role = 'customer'"));
        List<Object> params = new ArrayList<>();
        if (!Text.isBlank(search)) {
            where.add("(u.first_name ILIKE ? OR u.last_name ILIKE ? OR u.email ILIKE ? OR u.phone ILIKE ?)");
            String like = "%" + search.trim() + "%";
            for (int i = 0; i < 4; i++) params.add(like);
        }
        if (status != null) { where.add("u.status = ?"); params.add(status); }
        String whereSql = "WHERE " + String.join(" AND ", where);
        Object[] args = params.toArray();
        var items = db.rows("""
                SELECT u.*,
                  (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) AS order_count,
                  (SELECT COALESCE(SUM(total_amount),0) FROM orders o WHERE o.user_id = u.id AND o.status <> 'cancelled') AS total_spent,
                  (SELECT COUNT(*) FROM deliveries d WHERE d.staff_id = u.id AND d.delivery_status = 'delivered') AS deliveries_done
                FROM users u """ + " " + whereSql + " ORDER BY u.created_at DESC LIMIT " + paging.limit() + " OFFSET " + paging.offset(), args)
                .stream().map(u -> {
                    var m = AuthService.toPublicUser(u);
                    m.remove("permissions");
                    m.put("lastLoginAt", u.ts("last_login_at"));
                    m.put("orderCount", u.longOr0("order_count"));
                    m.put("totalSpent", u.money("total_spent"));
                    m.put("deliveriesDone", u.longOr0("deliveries_done"));
                    return m;
                }).toList();
        return paging.result(items, db.count("SELECT COUNT(*) FROM users u " + whereSql, args));
    }

    public Map<String, Object> customerDetail(int id) {
        Row u = db.one("SELECT * FROM users WHERE id = ? AND role = 'customer'", id)
                .orElseThrow(() -> ApiException.notFound("Customer not found"));
        var orders = db.rows("""
                SELECT id, order_number, total_amount, status, payment_status, delivery_date, created_at
                FROM orders WHERE user_id = ? ORDER BY created_at DESC LIMIT 50""", id).stream()
                .map(o -> Json.obj("id", o.integer("id"), "orderNumber", o.str("order_number"), "total", o.money("total_amount"),
                        "status", o.str("status"), "paymentStatus", o.str("payment_status"), "deliveryDate", o.date("delivery_date"),
                        "createdAt", o.ts("created_at")))
                .toList();
        var m = AuthService.toPublicUser(u);
        m.remove("permissions");
        m.put("lastLoginAt", u.ts("last_login_at"));
        m.put("addresses", addresses(id));
        m.put("orders", orders);
        return m;
    }

    @Transactional
    public Map<String, Object> adminUpdate(int id, UserController.AdminUserBody d, int actorId, String role) {
        db.one("SELECT 1 FROM users WHERE id = ? AND role = ?", id, role).orElseThrow(() -> ApiException.notFound("User not found"));
        assertEmailFree(lower(d.email()), id);
        db.update("""
                UPDATE users SET first_name = COALESCE(?, first_name), last_name = COALESCE(?, last_name),
                  email = COALESCE(?, email), phone = COALESCE(?, phone),
                  staff_role = CASE WHEN role = 'staff' THEN COALESCE(?, staff_role) ELSE NULL END,
                  status = COALESCE(?, status), updated_at = NOW() WHERE id = ?""",
                Text.trim(d.firstName()), Text.trim(d.lastName()), lower(d.email()), Text.cleanPhone(d.phone()),
                d.staffRole(), d.status(), id);
        if ("disabled".equals(d.status())) {
            // kick the user out of every active session
            db.update("UPDATE refresh_tokens SET revoked_at = NOW() WHERE user_id = ? AND revoked_at IS NULL", id);
        }
        events.publish("user.updated_by_admin", Json.obj("actorId", actorId, "entity", "user", "entityId", id));
        return AuthService.toPublicUser(user(id));
    }

    public Map<String, Object> createStaff(UserController.StaffBody d, int actorId) {
        assertEmailFree(lower(d.email()), 0);
        int id = db.insertId("""
                INSERT INTO users (first_name, last_name, email, phone, password_hash, role, staff_role)
                VALUES (?,?,?,?,?,'staff',?) RETURNING id""",
                d.firstName().trim(), d.lastName().trim(), lower(d.email()), Text.cleanPhone(d.phone()), encoder.encode(d.password()), d.staffRole());
        Row u = user(id);
        events.publish("staff.created", Json.obj("actorId", actorId, "entity", "user", "entityId", id, "userId", id,
                "email", u.str("email"), "firstName", u.str("first_name"), "staffRole", u.str("staff_role")));
        return AuthService.toPublicUser(u);
    }

    @Transactional
    public void deleteStaff(int id, int actorId) {
        if (db.count("SELECT COUNT(*) FROM users WHERE id = ? AND role = 'staff'", id) == 0) throw ApiException.notFound("Staff member not found");
        // unassign open deliveries so they can be re-assigned
        db.update("UPDATE deliveries SET staff_id = NULL, delivery_status = 'pending' WHERE staff_id = ? AND delivery_status = 'assigned'", id);
        db.update("DELETE FROM users WHERE id = ?", id);
        events.publish("staff.deleted", Json.obj("actorId", actorId, "entity", "user", "entityId", id));
    }
}
