package com.dating.common.errors;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        String code,
        String message,
        String traceId,
        Map<String, String> fieldErrors
) {
    public static ApiErrorResponse of(String code, String message, String traceId) {
        return new ApiErrorResponse(code, message, traceId, null);
    }

    public static ApiErrorResponse of(String code, String message, String traceId, Map<String, String> fieldErrors) {
        return new ApiErrorResponse(code, message, traceId, fieldErrors);
    }
}
