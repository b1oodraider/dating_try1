package com.dating.profiles.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * All fields optional: null means "leave unchanged". Trimmed-length rules (e.g. a name of spaces)
 * are checked in the service, after trimming.
 */
public record ProfileUpdateDto(
        @Size(max = 50, message = "Имя должно быть от 2 до 50 символов")
        String displayName,

        @Min(value = 18, message = "Возраст должен быть не менее 18 лет")
        @Max(value = 100, message = "Некорректный возраст")
        Integer age,

        @Size(max = 60, message = "Город не должен превышать 60 символов")
        String city,

        @Size(max = 1000, message = "Описание «О себе» не должно превышать 1000 символов")
        String aboutText,

        @Size(max = 10, message = "Можно выбрать не более 10 карточек")
        List<String> tagCodes,

        @Size(max = 200, message = "Статус на сегодня не должен превышать 200 символов")
        String dailyStatusText
) {}
