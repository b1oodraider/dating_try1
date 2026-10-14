package com.dating.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record OtpVerifyDto(
        @NotNull(message = "challengeId обязателен")
        UUID challengeId,

        @NotBlank(message = "Код подтверждения обязателен")
        @Pattern(regexp = "^[0-9]{6}$", message = "Код подтверждения должен состоять из 6 цифр")
        String code
) {}
