package rw.bloomco.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import rw.bloomco.config.AppProperties;

/**
 * Spring Security setup.
 *  - Stateless JWT bearer authentication for the REST API (JwtAuthFilter).
 *  - Authorization is declared per endpoint with @PreAuthorize("hasAuthority('permission')").
 *  - OAuth2 Authorization Code + PKCE login with Google (only when credentials are configured);
 *    an HTTP session exists only for the few seconds of that redirect handshake.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    public static final String OAUTH_BASE = "/api/auth/oauth";
    public static final String REMEMBER_ATTR = "bloom_oauth_remember";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /** The JWT filter runs inside the security chain only, not a second time as a servlet filter. */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtFilterRegistration(JwtAuthFilter filter) {
        var reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    @ConditionalOnExpression("!'${app.google.client-id:}'.isEmpty() and !'${app.google.client-secret:}'.isEmpty()")
    public ClientRegistrationRepository clientRegistrationRepository(AppProperties props) {
        return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(props.google().clientId())
                .clientSecret(props.google().clientSecret())
                .redirectUri(props.google().redirectUri())
                .scope("openid", "email", "profile")
                .build());
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtFilter, ObjectMapper mapper,
            ObjectProvider<ClientRegistrationRepository> oauthClients, OAuthLoginSuccessHandler oauthSuccess,
            AppProperties props) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(corsSource(props)))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; img-src 'self'; frame-ancestors 'none'")))
                // Endpoint-level rules live on the controllers (@PreAuthorize); everything else is public.
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeError(res, mapper, 401, "Authentication required"))
                        .accessDeniedHandler((req, res, ex) ->
                                writeError(res, mapper, 403, "You do not have permission to perform this action")))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        ClientRegistrationRepository clients = oauthClients.getIfAvailable();
        if (clients != null) {
            http.oauth2Login(o -> o
                    .authorizationEndpoint(ae -> ae.baseUri(OAUTH_BASE).authorizationRequestResolver(pkceResolver(clients)))
                    .redirectionEndpoint(re -> re.baseUri(OAUTH_BASE + "/*/callback"))
                    .successHandler(oauthSuccess)
                    .failureHandler((req, res, ex) ->
                            res.sendRedirect(props.clientUrl() + "/login?error=oauth_failed")));
        }
        return http.build();
    }

    /** Adds PKCE (S256) to the Google request and remembers the "remember me" choice for the callback. */
    private OAuth2AuthorizationRequestResolver pkceResolver(ClientRegistrationRepository clients) {
        var delegate = new DefaultOAuth2AuthorizationRequestResolver(clients, OAUTH_BASE);
        delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()
                .andThen(b -> b.additionalParameters(p -> p.put("prompt", "select_account"))));
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest req) {
                OAuth2AuthorizationRequest r = delegate.resolve(req);
                if (r != null) req.getSession(true).setAttribute(REMEMBER_ATTR, "1".equals(req.getParameter("remember")));
                return r;
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest req, String registrationId) {
                return delegate.resolve(req, registrationId);
            }
        };
    }

    private CorsConfigurationSource corsSource(AppProperties props) {
        var cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of(props.clientUrl()));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    private static void writeError(HttpServletResponse res, ObjectMapper mapper, int status, String message)
            throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), Map.of("message", message));
    }
}
