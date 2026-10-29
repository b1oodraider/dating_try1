package com.dating.safety.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Words a profile text may not contain (docs/trust-safety.md, «Фильтр текстов»): obscene language, offers of sex for
 * money and explicit sexual acts, drugs. Checked when a profile is saved; the person sees what to fix.
 * <p>
 * Text is normalized first: case, «ё», Latin look-alikes and digits used as letters («xy0», «p1zd»), and letters
 * stretched by repeats or separators inside a word («х-у-й», «пииизд»). Roots are matched at the start of a word
 * (with the usual prefixes), so «страхует», «себе», «команда» or «употреблять» pass.
 * shortcut: a fixed dictionary; misspellings beyond these rules get through and are left to reports and moderators —
 * a trained classifier or a moderation API would catch more.
 */
public final class ContentPolicy {

    public enum Category { OBSCENE, SEX_SERVICES, DRUGS }

    private static final Map<Category, String> MESSAGES = Map.of(
            Category.OBSCENE, "Уберите нецензурные слова",
            Category.SEX_SERVICES, "Нельзя писать об интимных услугах и откровенных подробностях",
            Category.DRUGS, "Нельзя упоминать наркотики");

    private static final String PREFIX = "(?:на|по|о|об|от|за|до|ни|при|вы|у|раз|рас|с|пере|недо|под|про)?";

    private static final List<Map.Entry<Category, Pattern>> RULES = List.of(
            rule(Category.OBSCENE, PREFIX + "ху[йеёяию]"),
            rule(Category.OBSCENE, "\\p{L}*пизд"),
            rule(Category.OBSCENE, PREFIX + "[её]б(?:а|у|л|н|и|ё|о|ыв)"),
            rule(Category.OBSCENE, "бля(?:д|т|$)"),
            rule(Category.OBSCENE, "муда(?:к|ч)|мудил|мудозвон"),
            rule(Category.OBSCENE, "залуп|шлюх|г[ао]ндон|пид(?:о|а)?р"),
            // Transliteration: «huy», «pizdec», «blyat».
            rule(Category.OBSCENE, "(?:na|po|o|za)?(?:hu|xu)[iyj]|\\p{L}*pizd|bl[yj]a[dt]|(?:za|vy|ot|u)?y?eba[lnt]"),
            rule(Category.SEX_SERVICES, "проститу|эскорт|индивидуалк|минет|кунилинг|куни$|анал(?:ьн|$)|оральн"
                    + "|оргия|оргии|содержанк|спонсор(?:а|ом)?$"),
            rule(Category.DRUGS, "мефедрон|гашиш|кокаин|амфетамин|героин|экстази|мдма|mdma|спайс|марихуан"));

    /** Phrases spread over several words («интим не предлагать» is a usual disclaimer and passes). */
    private static final List<Map.Entry<Category, Pattern>> PHRASES = List.of(
            Map.entry(Category.SEX_SERVICES, Pattern.compile("секс\\p{L}* (?:за|по) (?:деньг|оплат|телефон)")),
            Map.entry(Category.SEX_SERVICES, Pattern.compile("(?:интим|вирт) (?:за|по) ")),
            Map.entry(Category.SEX_SERVICES, Pattern.compile("ищу спонсор")));

    private static final Map<Character, Character> LOOK_ALIKES = Map.ofEntries(
            Map.entry('a', 'а'), Map.entry('b', 'в'), Map.entry('c', 'с'), Map.entry('e', 'е'), Map.entry('h', 'н'),
            Map.entry('k', 'к'), Map.entry('m', 'м'), Map.entry('o', 'о'), Map.entry('p', 'р'), Map.entry('t', 'т'),
            Map.entry('x', 'х'), Map.entry('y', 'у'), Map.entry('u', 'и'), Map.entry('0', 'о'), Map.entry('3', 'з'),
            Map.entry('4', 'ч'), Map.entry('6', 'б'), Map.entry('ё', 'е'), Map.entry('@', 'а'), Map.entry('1', 'и'));

    private ContentPolicy() {
    }

    /** @return what to tell the person, or empty when the text is fine */
    public static Optional<String> violation(String text) {
        return category(text).map(MESSAGES::get);
    }

    static Optional<Category> category(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String original = text.toLowerCase(Locale.ROOT);
        // Latin words (names of bands, «mdma») are checked as they are and with look-alikes replaced.
        for (String variant : List.of(normalize(original, false), normalize(original, true))) {
            for (String word : variant.split(" ")) {
                for (Map.Entry<Category, Pattern> rule : RULES) {
                    if (rule.getValue().matcher(word).lookingAt()) {
                        return Optional.of(rule.getKey());
                    }
                }
            }
            for (Map.Entry<Category, Pattern> phrase : PHRASES) {
                if (phrase.getValue().matcher(variant).find()) {
                    return Optional.of(phrase.getKey());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Lower-case words of letters separated by single spaces: separators inside a word («х.у.й», «х у й» of single
     * letters) are dropped, repeated letters collapsed.
     */
    static String normalize(String lower, boolean lookAlikes) {
        StringBuilder mapped = new StringBuilder(lower.length());
        for (char ch : lower.toCharArray()) {
            Character replacement = lookAlikes || ch == 'ё' ? LOOK_ALIKES.get(ch) : null;
            mapped.append(replacement != null ? replacement : ch);
        }
        // «х у й», «х.у.й», «х-у-й»: single letters joined by separators become one word.
        String joined = mapped.toString().replaceAll("(?<=(?:^|[^\\p{L}])\\p{L})[\\s.\\-_*]+(?=\\p{L}(?:[^\\p{L}]|$))", "");
        joined = joined.replaceAll("(\\p{L})[.\\-_*]+(?=\\p{L})", "$1");
        return joined.replaceAll("[^\\p{L}]+", " ").replaceAll("(\\p{L})\\1+", "$1").trim();
    }

    private static Map.Entry<Category, Pattern> rule(Category category, String regex) {
        return Map.entry(category, Pattern.compile(regex));
    }
}
