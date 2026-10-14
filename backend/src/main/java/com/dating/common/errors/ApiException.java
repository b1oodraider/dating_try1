package com.dating.common.errors;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class ApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final Map<String, String> fieldErrors;
    private final Long retryAfterSeconds;

    public ApiException(String code, String message, HttpStatus status) {
        this(code, message, status, null, null);
    }

    private ApiException(String code, String message, HttpStatus status,
                         Map<String, String> fieldErrors, Long retryAfterSeconds) {
        super(message);
        this.code = code;
        this.status = status;
        this.fieldErrors = fieldErrors;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(code, message, HttpStatus.NOT_FOUND);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException("UNAUTHORIZED", message, HttpStatus.UNAUTHORIZED);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(code, message, HttpStatus.FORBIDDEN);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(code, message, HttpStatus.BAD_REQUEST);
    }

    public static ApiException validation(String code, String message, Map<String, String> fieldErrors) {
        return new ApiException(code, message, HttpStatus.BAD_REQUEST, Map.copyOf(fieldErrors), null);
    }

    public static ApiException tooManyRequests(String message, long retryAfterSeconds) {
        return new ApiException("RATE_LIMITED", message, HttpStatus.TOO_MANY_REQUESTS, null, Math.max(1, retryAfterSeconds));
    }

    public static ApiException payloadTooLarge(String code, String message) {
        return new ApiException(code, message, HttpStatus.CONTENT_TOO_LARGE);
    }

    public static ApiException unsupportedMediaType(String message) {
        return new ApiException("UNSUPPORTED_MEDIA_TYPE", message, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    public static ApiException serviceUnavailable(String code, String message) {
        return new ApiException(code, message, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
