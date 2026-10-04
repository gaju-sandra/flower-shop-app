package rw.bloomco.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.method.PreAuthorizeAuthorizationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.util.SimpleMethodInvocation;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Evaluates a controller's @PreAuthorize rule <em>before</em> the request body is read and validated,
 * so a caller without the permission gets 401/403 - never a 400 that would leak the request contract.
 * Method security still runs again on invocation (defence in depth).
 */
@Component
public class EarlyAuthorizationInterceptor implements HandlerInterceptor {

    private final PreAuthorizeAuthorizationManager manager = new PreAuthorizeAuthorizationManager();

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (!(handler instanceof HandlerMethod hm) || hm.getBean() instanceof String) return true;
        var invocation = new SimpleMethodInvocation(hm.getBean(), hm.getMethod());
        AuthorizationResult result = manager.authorize(() -> SecurityContextHolder.getContext().getAuthentication(), invocation);
        if (result != null && !result.isGranted()) throw new AccessDeniedException("Access denied");
        return true;
    }
}
