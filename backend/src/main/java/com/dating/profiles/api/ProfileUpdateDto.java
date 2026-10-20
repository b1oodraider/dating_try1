package com.dating.profiles.api;

import com.dating.profiles.domain.Gender;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * All fields optional: null means "leave unchanged" (height is removed with clearHeight = true). Trimmed-length rules (e.g. a name of spaces)
 * are checked in the service, after trimming.
 */
public record ProfileUpdateDto(
        @Size(max = 50, message = "Имя должно быть от 2 до 50 символов")
        String displayName,

        /** Legacy: sets an approximate birth date when birthDate is absent. */
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
        String dailyStatusText,

        @Min(value = 120, message = "Рост должен быть от 120 до 230 см")
        @Max(value = 230, message = "Рост должен быть от 120 до 230 см")
        Integer heightCm,

        @Size(max = 3, message = "Можно выбрать не более 3 целей")
        List<String> goalCodes,

        /** true removes the height from the profile; cannot be combined with heightCm. */
        Boolean clearHeight,

        /** Locked once the profile was published (changed by support). */
        Gender gender,

        /** Who I look for; may change any time. */
        Gender seeking,

        /** ISO date (yyyy-MM-dd), 18..100 years; locked once the profile was published. */
        LocalDate birthDate,

        @Min(value = 18, message = "Возраст должен быть от 18 до 100")
        @Max(value = 100, message = "Возраст должен быть от 18 до 100")
        Integer ageFrom,

        @Min(value = 18, message = "Возраст должен быть от 18 до 100")
        @Max(value = 100, message = "Возраст должен быть от 18 до 100")
        Integer ageTo,

        /** true removes the upper age limit; cannot be combined with ageTo. */
        Boolean clearAgeTo
) {}
