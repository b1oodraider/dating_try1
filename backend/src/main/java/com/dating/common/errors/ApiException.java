package com.dating.common.errors;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class ApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final Map<String, String> fieldErrors;
    private final Long retryAfterSeconds;
    private final Map<String, Object> details;

    public ApiException(String code, String message, HttpStatus status) {
        this(code, message, status, null, null, null);
    }

    private ApiException(String code, String message, HttpStatus status,
                         Map<String, String> fieldErrors, Long retryAfterSeconds, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.status = status;
        this.fieldErrors = fieldErrors;
        this.retryAfterSeconds = retryAfterSeconds;
        this.details = details;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    /**
     * 403 for a feature that is reserved for premium; clients use details.feature to show the paywall.
     */
    public static ApiException premiumRequired(String feature) {
        return new ApiException("PREMIUM_REQUIRED", "Эта функция доступна с премиумом", HttpStatus.FORBIDDEN,
                null, null, Map.of("feature", feature));
    }

    /**
     * 409 when a consumable (e.g. super like) is spent with an insufficient balance.
     */
    public static ApiException insufficientBalance(String itemCode, long available) {
        return new ApiException("INSUFFICIENT_BALANCE", "Недостаточно средств для этого действия", HttpStatus.CONFLICT,
                null, null, Map.of("itemCode", itemCode, "available", available));
    }

    /**
     * 403 for staff endpoints; details.roles lists the roles any of which would grant access.
     */
    public static ApiException roleRequired(java.util.List<String> roles) {
        return new ApiException("ROLE_REQUIRED", "Недостаточно прав для этого действия", HttpStatus.FORBIDDEN,
                null, null, Map.of("roles", roles));
    }

    /**
     * Any error with machine-readable details (e.g. 409 ITEM_NOT_AVAILABLE with the availability window).
     */
    public static ApiException withDetails(String code, String message, HttpStatus status, Map<String, Object> details) {
        // Values may be null (e.g. an open-ended window), so no Map.copyOf here.
        return new ApiException(code, message, status, null, null,
                java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(details)));
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(code, message, HttpStatus.CONFLICT);
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
        return new ApiException(code, message, HttpStatus.BAD_REQUEST, Map.copyOf(fieldErrors), null, null);
    }

    public static ApiException tooManyRequests(String message, long retryAfterSeconds) {
        return new ApiException("RATE_LIMITED", message, HttpStatus.TOO_MANY_REQUESTS, null, Math.max(1, retryAfterSeconds), null);
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
