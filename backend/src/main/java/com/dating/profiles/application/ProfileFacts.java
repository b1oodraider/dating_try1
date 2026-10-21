package com.dating.profiles.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed answers for profile facts (docs/matching.md, part 7). Codes are stored; labels are served by
 * GET /profile-options so every client shows the same words. Must match the CHECKs of changeset 031.
 */
public final class ProfileFacts {

    public static final Map<String, String> KIDS_HAS = ordered("YES", "Есть дети", "NO", "Детей нет");
    public static final Map<String, String> KIDS_PLANS = ordered(
            "WANT", "Хочу детей", "DONT_WANT", "Не хочу детей", "UNDECIDED", "Пока не решил(а)");
    public static final Map<String, String> SMOKING = ordered(
            "NO", "Не курю", "SOMETIMES", "Иногда", "CIGARETTES", "Курю сигареты", "VAPE", "Вейп, кальян");
    public static final Map<String, String> ALCOHOL = ordered(
            "NO", "Не пью", "RARELY", "Редко", "MODERATELY", "Умеренно", "OFTEN", "Часто");
    public static final Map<String, String> SPORT = ordered(
            "REGULARLY", "Регулярно", "SOMETIMES", "Иногда", "NO", "Не занимаюсь");
    public static final Map<String, String> PETS = ordered(
            "CAT", "Кошка", "DOG", "Собака", "OTHER", "Другие животные", "NONE", "Нет животных");
    public static final Map<String, String> EDUCATION = ordered(
            "SCHOOL", "Среднее", "COLLEGE", "Среднее специальное", "INCOMPLETE_HIGHER", "Неоконченное высшее",
            "HIGHER", "Высшее", "DEGREE", "Учёная степень");
    public static final Map<String, String> LANGUAGES = ordered(
            "RU", "Русский", "EN", "Английский", "DE", "Немецкий", "FR", "Французский", "ES", "Испанский",
            "IT", "Итальянский", "PT", "Португальский", "ZH", "Китайский", "JA", "Японский", "KO", "Корейский",
            "TR", "Турецкий", "AR", "Арабский", "HE", "Иврит", "UK", "Украинский", "BE", "Белорусский",
            "KK", "Казахский", "UZ", "Узбекский", "HY", "Армянский", "KA", "Грузинский", "AZ", "Азербайджанский",
            "TT", "Татарский", "PL", "Польский", "CS", "Чешский", "SR", "Сербский", "FI", "Финский");

    /** Facts that can be filtered on (the keys of fact_filters, see facts_match in changeset 031). */
    public static final Map<String, Map<String, String>> FILTERABLE = Map.of(
            "kidsHas", KIDS_HAS, "kidsPlans", KIDS_PLANS, "smoking", SMOKING, "alcohol", ALCOHOL,
            "sport", SPORT, "pets", PETS);

    public record Option(String code, String label) {
    }

    /** GET /profile-options. */
    public static Map<String, List<Option>> options() {
        Map<String, List<Option>> all = new LinkedHashMap<>();
        all.put("kidsHas", list(KIDS_HAS));
        all.put("kidsPlans", list(KIDS_PLANS));
        all.put("smoking", list(SMOKING));
        all.put("alcohol", list(ALCOHOL));
        all.put("sport", list(SPORT));
        all.put("pets", list(PETS));
        all.put("educationLevel", list(EDUCATION));
        all.put("languages", list(LANGUAGES));
        return all;
    }

    private static List<Option> list(Map<String, String> map) {
        return map.entrySet().stream().map(e -> new Option(e.getKey(), e.getValue())).toList();
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }

    private ProfileFacts() {
    }
}
