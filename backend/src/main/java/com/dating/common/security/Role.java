package com.dating.common.security;

import java.util.Arrays;
import java.util.Optional;

/**
 * Staff roles. Stored in account_roles and read from the DB on every check (never from the token),
 * so granting or revoking takes effect on the next request.
 */
public enum Role {
    /** Everything, including roles, authors, premium composition and the audit log. */
    ADMIN,
    /** Communication cards, relationship goals, catalog items and achievement definitions. */
    CONTENT_EDITOR,
    /** Report queue (/admin/reports); later also contest submissions. */
    MODERATOR;

    public static Optional<Role> parse(String value) {
        return Arrays.stream(values()).filter(r -> r.name().equalsIgnoreCase(value)).findFirst();
    }
}
