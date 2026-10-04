package rw.bloomco.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads "Authorization: Bearer &lt;jwt&gt;" and puts the AuthUser in the SecurityContext.
 * A present-but-invalid token is rejected with 401 so the SPA knows to refresh.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final ObjectMapper mapper;

    public JwtAuthFilter(JwtService jwt, ObjectMapper mapper) {
        this.jwt = jwt;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            AuthUser user = jwt.verify(header.substring(7).trim());
            if (user == null) {
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                mapper.writeValue(res.getOutputStream(), Map.of("message", "Session expired, please sign in again"));
                return;
            }
            var auth = new UsernamePasswordAuthenticationToken(user, null, user.authorities());
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(req, res);
    }
}
