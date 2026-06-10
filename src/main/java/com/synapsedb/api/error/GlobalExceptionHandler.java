package com.synapsedb.api.error;

import com.synapsedb.engine.exception.CapacityReachedException;
import com.synapsedb.engine.exception.InvalidParentException;
import com.synapsedb.engine.exception.InvalidRequestException;
import com.synapsedb.engine.exception.ThoughtNotFoundException;
import com.synapsedb.engine.exception.UnknownAgentException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * [D4] Single home that maps every engine/validation exception to a clean JSON
 * {@link ApiError}. The core's {@code assert} guards are OFF in production, so this
 * advice (plus {@code @Valid} on the DTOs) is the real trust boundary.
 *
 * <pre>
 *   UnknownAgentException        → 404
 *   ThoughtNotFoundException     → 404
 *   InvalidParentException       → 409
 *   InvalidRequestException      → 400
 *   @Valid / type / missing-param→ 400
 *   IllegalStateException (full) → 503
 *   anything else                → 500
 * </pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({UnknownAgentException.class, ThoughtNotFoundException.class})
    public ResponseEntity<ApiError> notFound(RuntimeException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req);
    }

    @ExceptionHandler(InvalidParentException.class)
    public ResponseEntity<ApiError> conflict(InvalidParentException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> invalidRequest(InvalidRequestException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> beanValidation(MethodArgumentNotValidException ex,
                                                   HttpServletRequest req) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, detail.isBlank() ? "validation failed" : detail, req);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> badParam(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req);
    }

    /** Engine signals "server full" with a dedicated CapacityReachedException on registration. */
    @ExceptionHandler(CapacityReachedException.class)
    public ResponseEntity<ApiError> serviceUnavailable(CapacityReachedException ex, HttpServletRequest req) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), req);
    }

    /** Spring 6.x uses this for unmatched routes; must return 404 not 500. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noResource(NoResourceFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "not found", req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal error", req);
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String message,
                                                  HttpServletRequest req) {
        ApiError body = ApiError.of(status.value(), status.getReasonPhrase(),
                message == null ? status.getReasonPhrase() : message, req.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
