package rw.bloomco.auth;

import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;
import rw.bloomco.config.AppProperties;

/** The httpOnly refresh-token cookie, scoped to /api/auth so no other endpoint ever receives it. */
public final class AuthCookies {

    public static final String REFRESH_COOKIE = "bloom_rt";

    private AuthCookies() {}

    /** "Remember me" -> persistent cookie; otherwise a browser-session cookie. */
    public static ResponseCookie refresh(AuthService.RefreshToken r, AppProperties props) {
        var b = ResponseCookie.from(REFRESH_COOKIE, r.token())
                .httpOnly(true)
                .secure(props.clientUrl().startsWith("https"))
                .sameSite("Lax")
                .path("/api/auth");
        if (r.remember()) b.maxAge(Duration.between(Instant.now(), r.expiresAt()));
        return b.build();
    }

    public static ResponseCookie clear() {
        return ResponseCookie.from(REFRESH_COOKIE, "").httpOnly(true).sameSite("Lax").path("/api/auth").maxAge(0).build();
    }
}
