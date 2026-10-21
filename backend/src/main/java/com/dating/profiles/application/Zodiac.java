package com.dating.profiles.application;

import java.time.LocalDate;
import java.time.MonthDay;

/** Western zodiac sign by birth date (tropical dates). */
public final class Zodiac {

    private static final MonthDay[] STARTS = {
            MonthDay.of(1, 20), MonthDay.of(2, 19), MonthDay.of(3, 21), MonthDay.of(4, 20), MonthDay.of(5, 21),
            MonthDay.of(6, 21), MonthDay.of(7, 23), MonthDay.of(8, 23), MonthDay.of(9, 23), MonthDay.of(10, 23),
            MonthDay.of(11, 22), MonthDay.of(12, 22)};
    private static final String[] SIGNS = {
            "Водолей", "Рыбы", "Овен", "Телец", "Близнецы", "Рак", "Лев", "Дева", "Весы", "Скорпион", "Стрелец",
            "Козерог"};

    private Zodiac() {
    }

    /** @return sign name in Russian, null without a birth date */
    public static String of(LocalDate birthDate) {
        if (birthDate == null) {
            return null;
        }
        MonthDay day = MonthDay.from(birthDate);
        for (int i = STARTS.length - 1; i >= 0; i--) {
            if (!day.isBefore(STARTS[i])) {
                return SIGNS[i];
            }
        }
        return "Козерог"; // 1–19 January
    }
}
