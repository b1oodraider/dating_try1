package com.dating.profiles.application;

import com.dating.profiles.domain.CommunicationTag;
import com.dating.profiles.domain.Profile;

import java.util.Comparator;
import java.util.List;

/**
 * Profile completeness as concrete advice (docs/trust-safety.md, block F): each tip has a weight, the percent is
 * the sum of the weights already done. Only a hint for the owner; the feed never ranks by it.
 * shortcut: editorial weights; recalibrate from behavior_metrics (e.g. inbound like rate with / without the item)
 * once there is enough data.
 */
public final class ProfileTips {

    /**
     * @param requiredToPublish without it the profile cannot be published (same rule as missingForPublish)
     */
    public record Tip(String code, String text, int weight, boolean done, boolean requiredToPublish) {
    }

    static final int ABOUT_MIN_LENGTH = 50;
    static final int GOOD_PHOTO_COUNT = 3;
    static final int CARD_CATEGORIES = 2;

    private ProfileTips() {
    }

    /** Not done first, the most valuable first; weights add up to 100. */
    public static List<Tip> of(Profile profile, long photoCount) {
        long cardCategories = profile.getTags().stream().filter(CommunicationTag::isActive)
                .map(CommunicationTag::getCategory).distinct().count();
        String about = profile.getAboutText() == null ? "" : profile.getAboutText().trim();
        return List.of(
                        new Tip("NAME", "Укажите имя", 10, !isBlank(profile.getDisplayName()), true),
                        new Tip("AGE", "Укажите дату рождения", 10, profile.getBirthDate() != null, true),
                        new Tip("CITY", "Укажите город", 10, !isBlank(profile.getCity()), true),
                        new Tip("FIRST_PHOTO", "Добавьте фотографию", 15, photoCount >= 1, true),
                        new Tip("MORE_PHOTOS", "Добавьте ещё фото: с " + GOOD_PHOTO_COUNT
                                + " и больше снимками вас проще узнать", 10, photoCount >= GOOD_PHOTO_COUNT, false),
                        new Tip("ABOUT", "Расскажите о себе — хотя бы пару предложений (от " + ABOUT_MIN_LENGTH
                                + " символов)", 15, about.length() >= ABOUT_MIN_LENGTH, false),
                        new Tip("CARDS", "Выберите карточки хотя бы в " + CARD_CATEGORIES
                                + " категориях — по ним считается процент совпадения", 10, cardCategories >= CARD_CATEGORIES, false),
                        new Tip("GOALS", "Укажите, кого вы ищете", 8, !profile.getGoals().isEmpty(), false),
                        new Tip("VERIFIED", "Подтвердите профиль селфи — у проверенных анкет больше доверия", 8,
                                profile.isVerified(), false),
                        new Tip("HEIGHT", "Укажите рост", 4, profile.getHeightCm() != null, false))
                .stream()
                .sorted(Comparator.comparing(Tip::done).thenComparing(Tip::weight, Comparator.reverseOrder()))
                .toList();
    }

    public static int percent(List<Tip> tips) {
        return tips.stream().filter(Tip::done).mapToInt(Tip::weight).sum();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
