package rw.bloomco.config;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import rw.bloomco.security.EarlyAuthorizationInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serves uploaded product images and avatars from the upload directory at /uploads/**. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties props;
    private final EarlyAuthorizationInterceptor earlyAuthorization;

    public WebConfig(AppProperties props, EarlyAuthorizationInterceptor earlyAuthorization) {
        this.props = props;
        this.earlyAuthorization = earlyAuthorization;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(earlyAuthorization).addPathPatterns("/api/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of(props.uploadDir()).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location.endsWith("/") ? location : location + "/")
                .setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic().immutable());
    }
}
