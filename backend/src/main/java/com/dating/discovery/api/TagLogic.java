package com.dating.discovery.api;

import com.dating.common.errors.ApiException;

import java.util.Locale;

public enum TagLogic {
    /** Profile must have every requested tag. */
    AND,
    /** Profile must have at least one requested tag. */
    OR;

    public static TagLogic parse(String value) {
        if (value == null || value.isBlank()) {
            return OR;
        }
        try {
            return TagLogic.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("VALIDATION_ERROR", "tagLogic должен быть AND или OR");
        }
    }
}
