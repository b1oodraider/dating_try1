package com.dating.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Any human spelling is accepted ("+7 (999) 123-45-67", "89991234567"); the service normalizes it.
 */
public record OtpRequestDto(
        @NotBlank(message = "Номер телефона обязателен")
        @Size(max = 32, message = "Некорректный формат номера телефона")
        String phone
) {}
