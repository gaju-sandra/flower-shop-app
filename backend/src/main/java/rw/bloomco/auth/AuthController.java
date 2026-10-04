package rw.bloomco.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.bloomco.common.ApiException;
import rw.bloomco.common.Json;
import rw.bloomco.common.RateLimiter;
import rw.bloomco.common.Text;
import rw.bloomco.common.Validation;
import rw.bloomco.config.AppProperties;
import rw.bloomco.security.AuthUser;

/**
 * Authentication endpoints. The access token is returned in the body (kept in memory by the SPA);
 * the refresh token travels only in an httpOnly cookie scoped to /api/auth.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    static final String REFRESH_COOKIE = AuthCookies.REFRESH_COOKIE;
    private static final Duration AUTH_WINDOW = Duration.ofMinutes(15);
    private static final int AUTH_LIMIT = 20;

    private final AuthService auth;
    private final RateLimiter limiter;
    private final AppProperties props;

    public AuthController(AuthService auth, RateLimiter limiter, AppProperties props) {
        this.auth = auth;
        this.limiter = limiter;
        this.props = props;
    }

    // ------------------------------------------------------------------ request bodies

    public record RegisterRequest(
            @NotBlank(message = "First name is required") @Size(min = 2, max = 60, message = "Name is too short") String firstName,
            @NotBlank(message = "Last name is required") @Size(min = 2, max = 60, message = "Name is too short") String lastName,
            @NotBlank(message = Validation.EMAIL_MESSAGE) @Email(message = Validation.EMAIL_MESSAGE) @Size(max = 160) String email,
            @NotBlank(message = Validation.PHONE_MESSAGE) @Pattern(regexp = Validation.PHONE_REGEX, message = Validation.PHONE_MESSAGE) String phone,
            @NotBlank(message = Validation.PASSWORD_MESSAGE) @Pattern(regexp = Validation.PASSWORD_REGEX, message = Validation.PASSWORD_MESSAGE) String password,
            String confirmPassword,
            @jakarta.validation.constraints.NotNull(message = "Delivery address is required") @Valid Validation.Address address) {

        @AssertTrue(message = "Passwords do not match")
        public boolean isConfirmPassword() {
            return password != null && password.equals(confirmPassword);
        }

        String cleanPhone() {
            return Text.cleanPhone(phone);
        }
    }

    public record LoginRequest(
            @NotBlank(message = Validation.EMAIL_MESSAGE) @Email(message = Validation.EMAIL_MESSAGE) String email,
            @NotBlank(message = "Password is required") String password,
            Boolean remember) {}

    public record EmailRequest(@NotBlank(message = Validation.EMAIL_MESSAGE) @Email(message = Validation.EMAIL_MESSAGE) String email) {}

    public record ResetRequest(
            @NotBlank @Size(min = 10, message = "Invalid reset token") String token,
            @NotBlank(message = Validation.PASSWORD_MESSAGE) @Pattern(regexp = Validation.PASSWORD_REGEX, message = Validation.PASSWORD_MESSAGE) String password) {}

    // ------------------------------------------------------------------ endpoints

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest req) {
        limiter.check(req, "auth", AUTH_LIMIT, AUTH_WINDOW);
        var user = auth.register(body);
        return session(auth.issueSession(user, false), HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest body, HttpServletRequest req) {
        limiter.check(req, "auth", AUTH_LIMIT, AUTH_WINDOW);
        var user = auth.login(body.email().trim(), body.password());
        return session(auth.issueSession(user, Boolean.TRUE.equals(body.remember())), HttpStatus.OK);
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String token) {
        try {
            return session(auth.rotate(token), HttpStatus.OK);
        } catch (ApiException e) {
            return ResponseEntity.status(e.getStatus())
                    .header(HttpHeaders.SET_COOKIE, AuthCookies.clear().toString())
                    .body(Json.obj("message", e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String token) {
        auth.revoke(token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, AuthCookies.clear().toString()).build();
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> me(@AuthenticationPrincipal AuthUser principal) {
        var user = auth.findById(principal.id()).orElseThrow(() -> ApiException.notFound("User not found"));
        return Json.obj("user", AuthService.toPublicUser(user));
    }

    @PostMapping("/forgot-password")
    public Map<String, Object> forgotPassword(@Valid @RequestBody EmailRequest body, HttpServletRequest req) {
        limiter.check(req, "auth", AUTH_LIMIT, AUTH_WINDOW);
        String resetUrl = auth.requestPasswordReset(body.email().trim());
        var res = Json.obj("message", "If an account exists for that email, a reset link has been sent.");
        // Convenience for local demos where no SMTP server is configured.
        if (props.devMode() && resetUrl != null) res.put("devResetUrl", resetUrl);
        return res;
    }

    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(@Valid @RequestBody ResetRequest body, HttpServletRequest req) {
        limiter.check(req, "auth", AUTH_LIMIT, AUTH_WINDOW);
        auth.resetPassword(body.token(), body.password());
        return Json.obj("message", "Password updated. You can now sign in.");
    }

    @GetMapping("/oauth/providers")
    public Map<String, Object> providers() {
        return Json.obj("google", props.google().enabled());
    }

    /** Only reached when Google is not configured (otherwise Spring Security's OAuth2 filter handles it). */
    @GetMapping("/oauth/google")
    public void googleDisabled(HttpServletResponse res) throws java.io.IOException {
        res.sendRedirect(props.clientUrl() + "/login?error=oauth_disabled");
    }

    // ------------------------------------------------------------------ cookies

    private ResponseEntity<Map<String, Object>> session(AuthService.Session s, HttpStatus status) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, AuthCookies.refresh(s.refresh(), props).toString())
                .body(Json.obj("accessToken", s.accessToken(), "user", s.user()));
    }
}
