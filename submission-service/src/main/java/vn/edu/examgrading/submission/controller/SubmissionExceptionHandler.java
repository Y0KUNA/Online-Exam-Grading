package vn.edu.examgrading.submission.controller;

import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.examgrading.common.api.ApiError;
import vn.edu.examgrading.submission.storage.StorageUnavailableException;

/**
 * Renders {@link ResponseStatusException} thrown by the upload service as the
 * standard {@link ApiError} envelope so 404/409/422 keep the contract shape.
 * Ordered ahead of {@code common}'s {@code GlobalExceptionHandler} so these
 * specific types are not swallowed by its generic {@code Exception} handler.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SubmissionExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> status(ResponseStatusException ex, WebRequest request) {
        String code = ex.getReason() == null ? "ERROR" : ex.getReason();
        String message = ex.getReason() == null ? "Request failed" : ex.getReason();
        return ResponseEntity.status(ex.getStatusCode())
                .body(new ApiError(code, message, requestId(request), Map.of()));
    }

    @ExceptionHandler(StorageUnavailableException.class)
    ResponseEntity<ApiError> storage(StorageUnavailableException ex, WebRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("STORAGE_UNAVAILABLE", "Object storage is not reachable",
                        requestId(request), Map.of()));
    }

    private String requestId(WebRequest request) {
        String value = request.getHeader("X-Request-Id");
        try {
            return UUID.fromString(value).toString();
        } catch (RuntimeException ignored) {
            return UUID.randomUUID().toString();
        }
    }
}
