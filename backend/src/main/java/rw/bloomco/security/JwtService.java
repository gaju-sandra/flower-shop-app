package rw.bloomco.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;
import rw.bloomco.config.AppProperties;

/** Issues and verifies short-lived HS256 access tokens. */
@Service
public class JwtService {

    private static final String ISSUER = "bloom-api";
    private final SecretKey key;
    private final Duration ttl;

    public JwtService(AppProperties props) {
        // derive a 256-bit key from the configured secret so short dev secrets still work
        this.key = Keys.hmacShaKeyFor(sha256(props.jwt().accessSecret()));
        this.ttl = Duration.ofMinutes(props.jwt().accessTtlMinutes());
    }

    public String issue(int userId, String role, String name) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuer(ISSUER)
                .claim("role", role)
                .claim("name", name)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** @return the principal, or null when the token is missing, forged or expired. */
    public AuthUser verify(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).requireIssuer(ISSUER).build().parseSignedClaims(token).getPayload();
            return new AuthUser(Integer.parseInt(c.getSubject()), c.get("role", String.class), c.get("name", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
