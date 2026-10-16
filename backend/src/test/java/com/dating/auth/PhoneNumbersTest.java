package com.dating.auth;

import com.dating.auth.domain.PhoneNumbers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhoneNumbersTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "+7 (999) 123-45-67",
            "+79991234567",
            "79991234567",
            "8 999 123 45 67",
            "89991234567",
            "9991234567",
            " 8(999)123-45-67 "
    })
    void allSpellingsOfOneNumberNormalizeToTheSameValue(String raw) {
        assertEquals("79991234567", PhoneNumbers.normalize(raw).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "123", "+7 999", "abc9991234567", "+7 999 123 45 67 89 01 23", "<script>"})
    void rejectsNonPhones(String raw) {
        assertTrue(PhoneNumbers.normalize(raw).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "79991234567, +7 *** ***-**-67",
            "380501234567, +*** ***-**-67"
    })
    void masksAllButLastTwoDigits(String normalized, String expected) {
        assertEquals(expected, PhoneNumbers.mask(normalized));
    }
}
