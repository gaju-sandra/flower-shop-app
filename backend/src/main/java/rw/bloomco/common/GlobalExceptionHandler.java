package rw.bloomco.common;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Turns every failure into the API's JSON error shape: {"message": "...", "details": [...]}. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message, Object details) {
        return ResponseEntity.status(status).body(Json.obj("message", message, "details", details));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        return body(e.getStatus(), e.getMessage(), e.getDetails());
    }

    /** Bean Validation on @RequestBody / @ModelAttribute. */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Map<String, Object>> validation(BindException e) {
        List<Map<String, String>> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        List<Map<String, String>> global = e.getBindingResult().getGlobalErrors().stream()
                .map(g -> Map.of("field", "", "message", String.valueOf(g.getDefaultMessage())))
                .toList();
        var all = new java.util.ArrayList<>(details);
        all.addAll(global);
        String message = all.isEmpty() ? "Invalid input" : all.get(0).get("message");
        return body(HttpStatus.BAD_REQUEST, message, all);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> constraint(ConstraintViolationException e) {
        var details = e.getConstraintViolations().stream()
                .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage()))
                .toList();
        return body(HttpStatus.BAD_REQUEST, details.isEmpty() ? "Invalid input" : details.get(0).get("message"), details);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "Malformed or invalid JSON body", null);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> badParam(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "Invalid request parameter", null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge() {
        return body(HttpStatus.BAD_REQUEST, "Images must be 3 MB or smaller", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> mediaType() {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported content type", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "Method " + e.getMethod() + " is not allowed here", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noRoute(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "Route " + e.getHttpMethod() + " /" + e.getResourcePath() + " not found", null);
    }

    /** @PreAuthorize failures: anonymous -> 401, signed in but lacking permission -> 403. */
    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<Map<String, Object>> denied() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean anonymous = auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated();
        return anonymous
                ? body(HttpStatus.UNAUTHORIZED, "Authentication required", null)
                : body(HttpStatus.FORBIDDEN, "You do not have permission to perform this action", null);
    }

    /** PostgreSQL constraint violations -> friendly 4xx. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException e) {
        String msg = String.valueOf(e.getMostSpecificCause().getMessage());
        if (msg.contains("duplicate key")) return body(HttpStatus.CONFLICT, "That record already exists", null);
        if (msg.contains("foreign key")) return body(HttpStatus.CONFLICT, "Record is referenced by other data", null);
        if (msg.contains("check constraint")) return body(HttpStatus.BAD_REQUEST, "A value is outside the allowed range", null);
        log.error("Data integrity error", e);
        return body(HttpStatus.BAD_REQUEST, "The data could not be saved", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side. Please try again.", null);
    }
}
