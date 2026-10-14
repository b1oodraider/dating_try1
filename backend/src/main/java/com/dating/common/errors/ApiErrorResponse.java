package com.dating.common.errors;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * @param details optional machine-readable context, e.g. {"feature": "..."} for PREMIUM_REQUIRED
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        String code,
        String message,
        String traceId,
        Map<String, String> fieldErrors,
        Map<String, Object> details
) {
    public static ApiErrorResponse of(String code, String message, String traceId) {
        return new ApiErrorResponse(code, message, traceId, null, null);
    }

    public static ApiErrorResponse of(String code, String message, String traceId, Map<String, String> fieldErrors) {
        return new ApiErrorResponse(code, message, traceId, fieldErrors, null);
    }
}
