package com.dating.auth.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Phone normalization: every human spelling of one number must map to the same account.
 * "+7 (999) 123-45-67", "8 999 123 45 67" and "9991234567" all become "79991234567".
 */
public final class PhoneNumbers {

    private static final Pattern ALLOWED_CHARS = Pattern.compile("^\\+?[0-9()\\-.\\s]{5,32}$");

    private PhoneNumbers() {
    }

    /**
     * @return digits only, with country code (11–15 digits), or empty if the input is not a phone number
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (!ALLOWED_CHARS.matcher(trimmed).matches()) {
            return Optional.empty();
        }
        boolean hasPlus = trimmed.startsWith("+");
        String digits = trimmed.replaceAll("\\D", "");

        if (!hasPlus && digits.length() == 11 && digits.startsWith("8")) {
            digits = "7" + digits.substring(1); // Russian trunk prefix: 8 999 ... == +7 999 ...
        } else if (!hasPlus && digits.length() == 10) {
            digits = "7" + digits;              // local number without country code
        }

        if (digits.length() < 11 || digits.length() > 15) {
            return Optional.empty();
        }
        return Optional.of(digits);
    }

    /**
     * Masked form safe to show in UI and store: only the last two digits are visible.
     */
    public static String mask(String normalizedDigits) {
        String last2 = normalizedDigits.substring(normalizedDigits.length() - 2);
        if (normalizedDigits.length() == 11 && normalizedDigits.startsWith("7")) {
            return "+7 *** ***-**-" + last2;
        }
        return "+*** ***-**-" + last2;
    }
}
