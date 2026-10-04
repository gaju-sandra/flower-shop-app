package rw.bloomco.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import rw.bloomco.auth.AuthCookies;
import rw.bloomco.auth.AuthService;
import rw.bloomco.config.AppProperties;

/**
 * Final step of the Google OAuth2 login: link / create the local account, issue our own
 * refresh-token cookie, drop the temporary HTTP session and send the browser back to the SPA,
 * which calls /api/auth/refresh to obtain its access token.
 */
@Component
public class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuthLoginSuccessHandler.class);
    private final AuthService auth;
    private final AppProperties props;

    public OAuthLoginSuccessHandler(AuthService auth, AppProperties props) {
        this.auth = auth;
        this.props = props;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest req, HttpServletResponse res, Authentication authentication)
            throws IOException {
        HttpSession session = req.getSession(false);
        boolean remember = session != null && Boolean.TRUE.equals(session.getAttribute(SecurityConfig.REMEMBER_ATTR));
        try {
            if (!(authentication.getPrincipal() instanceof OidcUser google)) {
                res.sendRedirect(props.clientUrl() + "/login?error=oauth_failed");
                return;
            }
            if (google.getEmail() == null || Boolean.FALSE.equals(google.getEmailVerified())) {
                res.sendRedirect(props.clientUrl() + "/login?error=oauth_unverified");
                return;
            }
            var user = auth.upsertOAuthUser(new AuthService.OAuthIdentity("google", google.getSubject(), google.getEmail(),
                    google.getGivenName(), google.getFamilyName(), google.getPicture()));
            if (!"active".equals(user.str("status"))) {
                res.sendRedirect(props.clientUrl() + "/login?error=account_disabled");
                return;
            }
            var s = auth.issueSession(user, remember);
            res.addHeader(HttpHeaders.SET_COOKIE, AuthCookies.refresh(s.refresh(), props).toString());
            res.sendRedirect(props.clientUrl() + "/oauth/callback");
        } catch (Exception e) {
            log.error("OAuth login failed: {}", e.getMessage());
            res.sendRedirect(props.clientUrl() + "/login?error=oauth_failed");
        } finally {
            // the API itself is stateless - discard the handshake session
            SecurityContextHolder.clearContext();
            if (session != null) {
                try {
                    session.invalidate();
                } catch (IllegalStateException ignored) {
                    // already invalidated
                }
            }
        }
    }
}
