package rw.bloomco.common;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** A business error that maps directly to an HTTP status + JSON {message, details}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final List<Map<String, String>> details;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, List<Map<String, String>> details) {
        super(message);
        this.status = status;
        this.details = details;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public List<Map<String, String>> getDetails() {
        return details;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public static ApiException forbidden() {
        return forbidden("You do not have permission to perform this action");
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }
}
