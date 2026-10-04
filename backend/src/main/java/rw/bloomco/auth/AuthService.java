package rw.bloomco.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bloomco.common.ApiException;
import rw.bloomco.common.Db;
import rw.bloomco.common.Json;
import rw.bloomco.common.Row;
import rw.bloomco.config.AppProperties;
import rw.bloomco.messaging.EventBus;
import rw.bloomco.security.JwtService;
import rw.bloomco.security.Permissions;

/** Registration, login, refresh-token rotation, password reset and OAuth2 account linking. */
@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Db db;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final EventBus events;
    private final AppProperties props;
    private final String dummyHash;

    public AuthService(Db db, PasswordEncoder encoder, JwtService jwt, EventBus events, AppProperties props) {
        this.db = db;
        this.encoder = encoder;
        this.jwt = jwt;
        this.events = events;
        this.props = props;
        this.dummyHash = encoder.encode("timing-equaliser");
    }

    /** A new session: short-lived access token + opaque refresh token (stored hashed). */
    public record Session(String accessToken, RefreshToken refresh, Map<String, Object> user) {}

    public record RefreshToken(String token, Instant expiresAt, boolean remember) {}

    // ------------------------------------------------------------------ mapping

    /** Public shape of a user - never exposes the password hash. */
    public static Map<String, Object> toPublicUser(Row u) {
        return Json.obj(
                "id", u.integer("id"),
                "firstName", u.str("first_name"),
                "lastName", u.str("last_name"),
                "email", u.str("email"),
                "phone", u.str("phone"),
                "role", u.str("role"),
                "staffRole", u.str("staff_role"),
                "status", u.str("status"),
                "avatarUrl", u.str("avatar_url"),
                "hasPassword", u.str("password_hash") != null,
                "oauthProvider", u.str("oauth_provider"),
                "createdAt", u.ts("created_at"),
                "permissions", Permissions.forRole(u.str("role")));
    }

    public Optional<Row> findByEmail(String email) {
        return db.one("SELECT * FROM users WHERE LOWER(email) = LOWER(?)", email);
    }

    public Optional<Row> findById(int id) {
        return db.one("SELECT * FROM users WHERE id = ?", id);
    }

    public String hash(String password) {
        return encoder.encode(password);
    }

    // ------------------------------------------------------------------ sessions

    public Session issueSession(Row user, boolean remember) {
        int id = user.integer("id");
        RefreshToken refresh = createRefreshToken(id, remember);
        db.update("UPDATE users SET last_login_at = NOW() WHERE id = ?", id);
        return new Session(accessToken(user), refresh, toPublicUser(user));
    }

    private String accessToken(Row user) {
        return jwt.issue(user.integer("id"), user.str("role"), user.str("first_name") + " " + user.str("last_name"));
    }

    private RefreshToken createRefreshToken(int userId, boolean remember) {
        String token = randomToken(48);
        int days = remember ? props.jwt().refreshTtlDaysRemember() : props.jwt().refreshTtlDays();
        Instant expiresAt = Instant.now().plus(days, ChronoUnit.DAYS);
        db.update("INSERT INTO refresh_tokens (user_id, token_hash, remember, expires_at) VALUES (?,?,?,?)",
                userId, sha256(token), remember, Timestamp.from(expiresAt));
        return new RefreshToken(token, expiresAt, remember);
    }

    /** Rotates a refresh token. Re-use of a revoked token revokes every session of that user. */
    @Transactional(noRollbackFor = ApiException.class)
    public Session rotate(String token) {
        if (token == null || token.isBlank()) throw ApiException.unauthorized("No session");
        Row stored = db.one("SELECT * FROM refresh_tokens WHERE token_hash = ? FOR UPDATE", sha256(token))
                .orElseThrow(() -> ApiException.unauthorized("Session not found"));
        int userId = stored.integer("user_id");
        if (stored.ts("revoked_at") != null) {
            db.update("UPDATE refresh_tokens SET revoked_at = NOW() WHERE user_id = ? AND revoked_at IS NULL", userId);
            throw ApiException.unauthorized("Session was revoked");
        }
        if (stored.ts("expires_at").isBefore(Instant.now())) throw ApiException.unauthorized("Session expired");
        Row user = findById(userId).filter(u -> "active".equals(u.str("status")))
                .orElseThrow(() -> ApiException.unauthorized("Account unavailable"));
        db.update("UPDATE refresh_tokens SET revoked_at = NOW() WHERE id = ?", stored.integer("id"));
        RefreshToken refresh = createRefreshToken(userId, Boolean.TRUE.equals(stored.bool("remember")));
        return new Session(accessToken(user), refresh, toPublicUser(user));
    }

    public void revoke(String token) {
        if (token == null || token.isBlank()) return;
        db.update("UPDATE refresh_tokens SET revoked_at = NOW() WHERE token_hash = ? AND revoked_at IS NULL", sha256(token));
    }

    // ------------------------------------------------------------------ register / login

    @Transactional
    public Row register(AuthController.RegisterRequest in) {
        if (findByEmail(in.email()).isPresent()) throw ApiException.conflict("An account with this email already exists");
        int id = db.insertId(
                "INSERT INTO users (first_name, last_name, email, phone, password_hash, role) VALUES (?,?,?,?,?,'customer') RETURNING id",
                in.firstName().trim(), in.lastName().trim(), in.email().trim().toLowerCase(), in.cleanPhone(), hash(in.password()));
        var a = in.address();
        if (a != null) {
            db.update("""
                    INSERT INTO addresses (user_id, label, recipient_name, phone, province, district, sector, street, is_default)
                    VALUES (?,'Home',?,?,?,?,?,?,TRUE)""",
                    id, in.firstName().trim() + " " + in.lastName().trim(), in.cleanPhone(),
                    a.province().trim(), a.district().trim(), a.sector().trim(), a.street().trim());
        }
        db.update("INSERT INTO cart (user_id) VALUES (?)", id);
        Row user = findById(id).orElseThrow();
        events.publish("user.registered", Json.obj("userId", id, "actorId", id, "entity", "user", "entityId", id,
                "email", user.str("email"), "firstName", user.str("first_name")));
        return user;
    }

    public Row login(String email, String password) {
        Optional<Row> found = findByEmail(email);
        // constant-ish time: always run bcrypt, even when the user does not exist
        String hash = found.map(u -> u.str("password_hash")).orElse(null);
        boolean ok = encoder.matches(password, hash != null ? hash : dummyHash);
        if (found.isEmpty() || !ok) {
            if (found.isPresent() && hash == null) {
                throw ApiException.unauthorized("This account uses Google sign-in. Continue with Google.");
            }
            throw ApiException.unauthorized("Incorrect email or password");
        }
        Row user = found.get();
        if (!"active".equals(user.str("status"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This account has been disabled. Please contact support.");
        }
        events.publish("auth.login", Json.obj("actorId", user.integer("id"), "entity", "user", "entityId", user.integer("id")));
        return user;
    }

    // ------------------------------------------------------------------ password reset

    /** @return the reset link (for local demos without SMTP), or null if no such active user. */
    public String requestPasswordReset(String email) {
        Optional<Row> found = findByEmail(email).filter(u -> "active".equals(u.str("status")));
        if (found.isEmpty()) return null; // don't reveal whether the email exists
        Row user = found.get();
        String token = randomToken(32);
        db.update("INSERT INTO password_resets (user_id, token_hash, expires_at) VALUES (?,?, NOW() + INTERVAL '30 minutes')",
                user.integer("id"), sha256(token));
        String resetUrl = props.clientUrl() + "/reset-password?token=" + token;
        events.publish("auth.password_reset_requested", Json.obj("userId", user.integer("id"), "actorId", user.integer("id"),
                "entity", "user", "entityId", user.integer("id"), "email", user.str("email"),
                "firstName", user.str("first_name"), "resetUrl", resetUrl));
        return resetUrl;
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        Row reset = db.one("""
                SELECT * FROM password_resets WHERE token_hash = ? AND used_at IS NULL AND expires_at > NOW() FOR UPDATE""",
                sha256(token)).orElseThrow(() -> ApiException.badRequest("This reset link is invalid or has expired"));
        int userId = reset.integer("user_id");
        db.update("UPDATE password_resets SET used_at = NOW() WHERE id = ?", reset.integer("id"));
        db.update("UPDATE users SET password_hash = ?, updated_at = NOW() WHERE id = ?", hash(newPassword), userId);
        db.update("UPDATE refresh_tokens SET revoked_at = NOW() WHERE user_id = ? AND revoked_at IS NULL", userId);
    }

    // ------------------------------------------------------------------ OAuth2

    public record OAuthIdentity(String provider, String subject, String email, String firstName, String lastName, String avatarUrl) {}

    /** Finds or creates the local account for a verified OAuth2 identity (Google). */
    @Transactional
    public Row upsertOAuthUser(OAuthIdentity id) {
        Optional<Row> linked = db.one("SELECT * FROM users WHERE oauth_provider = ? AND oauth_subject = ?", id.provider(), id.subject());
        if (linked.isPresent()) return linked.get();
        Optional<Row> existing = findByEmail(id.email());
        if (existing.isPresent()) {
            // link the identity to the existing account (email verified by the provider)
            db.update("""
                    UPDATE users SET oauth_provider = ?, oauth_subject = ?, avatar_url = COALESCE(avatar_url, ?), updated_at = NOW()
                    WHERE id = ?""", id.provider(), id.subject(), id.avatarUrl(), existing.get().integer("id"));
            return findById(existing.get().integer("id")).orElseThrow();
        }
        int userId = db.insertId("""
                INSERT INTO users (first_name, last_name, email, role, oauth_provider, oauth_subject, avatar_url)
                VALUES (?,?,?,'customer',?,?,?) RETURNING id""",
                id.firstName() == null ? "Flower" : id.firstName(), id.lastName() == null ? "Lover" : id.lastName(),
                id.email().toLowerCase(), id.provider(), id.subject(), id.avatarUrl());
        db.update("INSERT INTO cart (user_id) VALUES (?)", userId);
        events.publish("user.registered", Json.obj("userId", userId, "actorId", userId, "entity", "user", "entityId", userId,
                "email", id.email(), "firstName", id.firstName(), "provider", id.provider()));
        return findById(userId).orElseThrow();
    }

    // ------------------------------------------------------------------ helpers

    static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
