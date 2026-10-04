package rw.bloomco.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed view of the {@code app.*} section of application.yml. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String clientUrl,
        String serverUrl,
        String uploadDir,
        boolean seedOnStartup,
        Jwt jwt,
        Mongo mongo,
        Rabbit rabbit,
        Google google,
        Smtp smtp,
        Sms sms) {

    public record Jwt(String accessSecret, int accessTtlMinutes, int refreshTtlDays, int refreshTtlDaysRemember) {}

    public record Mongo(String uri) {
        public boolean enabled() {
            return uri != null && !uri.isBlank();
        }
    }

    public record Rabbit(String url) {
        public boolean enabled() {
            return url != null && !url.isBlank();
        }
    }

    public record Google(String clientId, String clientSecret, String redirectUri) {
        public boolean enabled() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }
    }

    public record Smtp(String host, int port, String username, String password, String from) {
        public boolean enabled() {
            return host != null && !host.isBlank();
        }
    }

    public record Sms(String apiKey, String sender) {}

    /** Local demo conveniences (e.g. returning the password-reset link) only without real SMTP. */
    public boolean devMode() {
        return smtp == null || !smtp.enabled();
    }
}
