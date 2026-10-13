package vn.edu.examgrading.identity.controller;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import vn.edu.examgrading.common.api.ApiError;
import vn.edu.examgrading.identity.service.IdentityAuthService;

@RestControllerAdvice
public class IdentityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(IdentityExceptionHandler.class);

    @ExceptionHandler(IdentityAuthService.InvalidCredentialsException.class)
    ResponseEntity<ApiError> invalidCredentials(WebRequest request) {
        log.warn("Authentication failed: code=INVALID_CREDENTIALS requestId={}", request.getHeader("X-Request-Id"));
        return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials", request);
    }

    @ExceptionHandler(IdentityAuthService.DuplicateUserException.class)
    ResponseEntity<ApiError> duplicate(IdentityAuthService.DuplicateUserException exception, WebRequest request) {
        log.warn("Duplicate user: code={} requestId={}", exception.getMessage(), request.getHeader("X-Request-Id"));
        return error(HttpStatus.CONFLICT, exception.getMessage(), "User already exists", request);
    }

    @ExceptionHandler(IdentityAuthService.UserNotFoundException.class)
    ResponseEntity<ApiError> userNotFound(WebRequest request) {
        log.warn("User not found: requestId={}", request.getHeader("X-Request-Id"));
        return error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found", request);
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> badRequest(Exception exception, WebRequest request) {
        log.warn("Bad request: reason={} requestId={}", exception.getMessage(), request.getHeader("X-Request-Id"));
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validationError(MethodArgumentNotValidException exception, WebRequest request) {
        FieldError fieldError = exception.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "Validation failed";
        log.warn("Validation error: message={} requestId={}", message, request.getHeader("X-Request-Id"));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> fallback(Exception exception, WebRequest request) {
        log.error("Unexpected identity error: requestId={}, error={}", request.getHeader("X-Request-Id"), exception.getMessage(), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error", request);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message, WebRequest request) {
        String id = request.getHeader("X-Request-Id"); try { UUID.fromString(id); } catch (RuntimeException ignored) { id = UUID.randomUUID().toString(); }
        return ResponseEntity.status(status).body(new ApiError(code, message, id, Map.of()));
    }
}
