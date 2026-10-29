package com.dating.testing;

import com.dating.accounts.application.AccountService;
import com.dating.profiles.api.InterestDto;
import com.dating.profiles.api.MyProfileDto;
import com.dating.profiles.api.ProfileUpdateDto;
import com.dating.profiles.application.ProfileFacts;
import com.dating.profiles.application.ProfileService;
import com.dating.profiles.domain.Artist;
import com.dating.profiles.domain.CommunicationTag;
import com.dating.profiles.domain.Gender;
import com.dating.profiles.domain.Interest;
import com.dating.profiles.domain.RelationshipGoal;
import com.dating.profiles.persistence.ArtistRepository;
import com.dating.profiles.persistence.CityRepository;
import com.dating.profiles.persistence.CommunicationTagRepository;
import com.dating.profiles.persistence.InterestRepository;
import com.dating.profiles.persistence.RelationshipGoalRepository;
import com.dating.safety.persistence.VerificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Fills seed profiles with everything a real one can have (interests with comments, music, facts, one card per
 * category, goals, height, zodiac, a location near the city centre, the «Проверен» badge), so testers see every
 * feature in the feed. Only what is missing is filled; picks are seeded by the account id, so a profile gets the
 * same values every run. Runs after each start (new deploy) and for every crowd profile.
 */
@Component
@ConditionalOnProperty(prefix = "app.testing", name = "enabled", havingValue = "true")
public class SeedProfileEnricher {

    private static final Logger log = LoggerFactory.getLogger(SeedProfileEnricher.class);

    /** Comments for interests (several variants each); an interest without an entry gets none. */
    static final Map<String, List<String>> COMMENTS = Map.ofEntries(
            Map.entry("COFFEE", List.of("флэт уайт и раф", "латте на овсяном", "эспрессо, без сахара")),
            Map.entry("TEA", List.of("улун и пуэр", "чай с чабрецом")),
            Map.entry("WINE", List.of("грузинские вина", "сухое красное")),
            Map.entry("RUNNING", List.of("по утрам, 5 км", "готовлюсь к полумарафону")),
            Map.entry("YOGA", List.of("хатха по выходным", "по утрам дома")),
            Map.entry("GYM", List.of("3 раза в неделю", "силовые")),
            Map.entry("SWIMMING", List.of("бассейн по вечерам", "открытая вода летом")),
            Map.entry("CYCLING", List.of("по набережным", "шоссейник")),
            Map.entry("FOOTBALL", List.of("болею за Зенит", "играю по средам")),
            Map.entry("TENNIS", List.of("учусь, ищу пару", "грунт летом")),
            Map.entry("DANCING", List.of("бачата", "хип-хоп")),
            Map.entry("HIKING", List.of("Карелия и Алтай", "Кавказ")),
            Map.entry("SKIING", List.of("Шерегеш", "Красная Поляна")),
            Map.entry("SNOWBOARD", List.of("Шерегеш каждую зиму")),
            Map.entry("CLIMBING", List.of("боулдеринг")),
            Map.entry("SUP", List.of("летом на Неве")),
            Map.entry("MUSEUMS", List.of("Пушкинский и Эрмитаж", "люблю музеи науки")),
            Map.entry("THEATRE", List.of("МХТ и Гоголь-центр", "камерные спектакли")),
            Map.entry("EXHIBITIONS", List.of("современное искусство", "фотовыставки")),
            Map.entry("CONCERTS", List.of("инди-группы в клубах", "классика в филармонии")),
            Map.entry("STANDUP", List.of("открытые микрофоны")),
            Map.entry("ARCHITECTURE", List.of("конструктивизм", "модерн Петербурга")),
            Map.entry("DRAWING", List.of("акварель", "скетчи в кафе")),
            Map.entry("PHOTOGRAPHY", List.of("плёнка", "уличная съёмка")),
            Map.entry("INSTRUMENT", List.of("гитара", "фортепиано")),
            Map.entry("SINGING", List.of("пою в хоре")),
            Map.entry("POTTERY", List.of("гончарный круг по субботам")),
            Map.entry("COOKING", List.of("плов и паста", "азиатская кухня")),
            Map.entry("BAKING", List.of("круассаны", "хлеб на закваске")),
            Map.entry("RESTAURANTS", List.of("ищу новые места", "грузинская кухня")),
            Map.entry("ASIAN_FOOD", List.of("рамен и том ям")),
            Map.entry("TRAVELLING", List.of("Грузия и Алтай, хочу в Японию", "была в 20 странах")),
            Map.entry("ROAD_TRIPS", List.of("по Золотому кольцу", "на машине до Байкала")),
            Map.entry("MOUNTAINS", List.of("Эльбрус в планах")),
            Map.entry("CITY_TRIPS", List.of("Казань и Калининград")),
            Map.entry("LANGUAGES", List.of("учу испанский", "английский и немного японского")),
            Map.entry("VIDEO_GAMES", List.of("Baldur's Gate 3", "инди-игры")),
            Map.entry("BOARD_GAMES", List.of("Каркассон и Кодовые имена", "длинные евроигры")),
            Map.entry("CHESS", List.of("рейтинг 1600 на lichess")),
            Map.entry("QUIZZES", List.of("Квиз, плиз! по четвергам")),
            Map.entry("MOVIES", List.of("Нолан и Вильнёв", "авторское кино")),
            Map.entry("SERIES", List.of("Во все тяжкие и Тед Лассо", "британские детективы")),
            Map.entry("COMEDY", List.of("всё с Джимом Керри", "комедии 90-х")),
            Map.entry("SCI_FI", List.of("Дюна", "Стругацкие")),
            Map.entry("ANIME", List.of("Миядзаки")),
            Map.entry("HORROR", List.of("только хорошие, вроде Оно")),
            Map.entry("BOOKS", List.of("Пелевин и Улицкая", "нон-фикшн про мозг")),
            Map.entry("FANTASY", List.of("Сапковский и Толкин")),
            Map.entry("DETECTIVES", List.of("Агата Кристи")),
            Map.entry("NONFICTION", List.of("про экономику и психологию")),
            Map.entry("PODCASTS", List.of("научпоп в дороге")),
            Map.entry("PSYCHOLOGY", List.of("читаю про привязанность")),
            Map.entry("PROGRAMMING", List.of("пишу на Kotlin", "пет-проекты по вечерам")),
            Map.entry("SPACE", List.of("смотрю запуски")),
            Map.entry("CARS", List.of("старые японские машины")),
            Map.entry("DOGS", List.of("у меня корги", "хочу лабрадора")),
            Map.entry("CATS", List.of("британец по имени Бублик", "две кошки")),
            Map.entry("HOUSE_PLANTS", List.of("монстеры и фикусы")),
            Map.entry("GARDENING", List.of("помидоры на даче")),
            Map.entry("ROCK", List.of("британский рок 90-х")),
            Map.entry("JAZZ", List.of("джем-сейшены по пятницам")),
            Map.entry("ELECTRONIC", List.of("техно и хаус")),
            Map.entry("VINYL", List.of("собираю пластинки")),
            Map.entry("MEDITATION", List.of("10 минут по утрам")),
            Map.entry("VOLUNTEERING", List.of("в приюте для собак")),
            Map.entry("KARAOKE", List.of("пою Земфиру")),
            Map.entry("WALKING", List.of("по 10 тысяч шагов", "вечерами по центру")),
            Map.entry("NATURE_WALKS", List.of("в лесу за городом")));

    private static final List<String> PROFESSIONS = List.of("Дизайнер интерфейсов", "Инженер-программист",
            "Врач-терапевт", "Учитель английского", "Маркетолог", "Архитектор", "Юрист", "Аналитик данных",
            "Фотограф", "Менеджер проектов", "Бариста", "Психолог", "Инженер-проектировщик", "Шеф-повар");
    private static final List<String> UNIVERSITIES = List.of("МГУ", "СПбГУ", "ВШЭ", "МФТИ", "ИТМО", "МГИМО",
            "КФУ", "УрФУ", "НГУ", "Бауманка");
    private static final List<String> STATUSES = List.of("Иду на выставку, кто со мной?",
            "Ищу компанию на квиз в четверг", "Сегодня пеку круассаны", "Вечером гуляю по набережной",
            "Хочу в кино на новый фильм Вильнёва");

    private final AccountService accountService;
    private final ProfileService profileService;
    private final InterestRepository interests;
    private final ArtistRepository artists;
    private final CommunicationTagRepository tags;
    private final RelationshipGoalRepository goals;
    private final CityRepository cities;
    private final VerificationRepository verification;
    private final JsonMapper jsonMapper;

    public SeedProfileEnricher(AccountService accountService, ProfileService profileService,
                               InterestRepository interests, ArtistRepository artists, CommunicationTagRepository tags,
                               RelationshipGoalRepository goals, CityRepository cities,
                               VerificationRepository verification, JsonMapper jsonMapper) {
        this.accountService = accountService;
        this.profileService = profileService;
        this.interests = interests;
        this.artists = artists;
        this.tags = tags;
        this.goals = goals;
        this.cities = cities;
        this.verification = verification;
        this.jsonMapper = jsonMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void enrichAllAfterStart() {
        int done = 0;
        for (UUID accountId : accountService.activeSeedAccountIds()) {
            try {
                enrich(accountId);
                done++;
            } catch (RuntimeException e) {
                // One odd profile must not stop the others or the start.
                log.warn("Seed profile {} not enriched: {}", accountId, e.toString());
            }
        }
        log.info("Seed profiles enriched: {}", done);
    }

    /** Fills what the seed profile lacks; returns the profile after. */
    public MyProfileDto enrich(UUID accountId) {
        MyProfileDto p = profileService.getMyProfile(accountId);
        Random random = new Random(accountId.getMostSignificantBits() ^ accountId.getLeastSignificantBits());
        Map<String, Object> update = new LinkedHashMap<>();

        List<String> chosen = p.interests().stream().map(InterestDto::code).toList();
        if (chosen.isEmpty()) {
            List<Interest> all = new ArrayList<>(interests.findAllByActiveTrue());
            // Interests with comments first: those make the feature visible.
            all.sort((a, b) -> Boolean.compare(COMMENTS.containsKey(b.getCode()), COMMENTS.containsKey(a.getCode())));
            List<Interest> withComments = new ArrayList<>(all.subList(0, Math.min(40, all.size())));
            Collections.shuffle(withComments, random);
            chosen = withComments.stream().limit(5 + random.nextInt(3)).map(Interest::getCode).toList();
            update.put("interestCodes", chosen);
        }
        if (p.interests().stream().allMatch(i -> i.comment() == null)) {
            Map<String, String> comments = new LinkedHashMap<>();
            for (String code : chosen) {
                List<String> variants = COMMENTS.get(code);
                if (variants != null && random.nextInt(10) < 7) {
                    comments.put(code, variants.get(random.nextInt(variants.size())));
                }
            }
            if (!comments.isEmpty()) {
                update.put("interestComments", comments);
            }
        }
        if (p.artists().isEmpty()) {
            List<Artist> known = new ArrayList<>(artists.findAll(PageRequest.of(0, 150)).getContent());
            Collections.shuffle(known, random);
            update.put("artistIds", known.stream().limit(2 + random.nextInt(3)).map(Artist::getId).toList());
        }
        var facts = p.facts();
        putIfMissing(update, "kidsHas", facts.kidsHas(), pick(ProfileFacts.KIDS_HAS, random));
        putIfMissing(update, "kidsPlans", facts.kidsPlans(), pick(ProfileFacts.KIDS_PLANS, random));
        putIfMissing(update, "smoking", facts.smoking(), random.nextInt(10) < 7 ? "NO" : pick(ProfileFacts.SMOKING, random));
        putIfMissing(update, "alcohol", facts.alcohol(), pick(ProfileFacts.ALCOHOL, random));
        putIfMissing(update, "sport", facts.sport(), pick(ProfileFacts.SPORT, random));
        if (facts.pets().isEmpty()) {
            update.put("pets", List.of(pick(ProfileFacts.PETS, random)));
        }
        putIfMissing(update, "profession", facts.profession(), PROFESSIONS.get(random.nextInt(PROFESSIONS.size())));
        putIfMissing(update, "educationLevel", facts.educationLevel(), random.nextBoolean() ? "HIGHER" : pick(ProfileFacts.EDUCATION, random));
        putIfMissing(update, "university", facts.university(), UNIVERSITIES.get(random.nextInt(UNIVERSITIES.size())));
        if (facts.languages().isEmpty()) {
            update.put("languages", random.nextBoolean() ? List.of("RU", "EN") : List.of("RU"));
        }
        if (p.tags().size() < 3) {
            Map<String, List<String>> byCategory = new LinkedHashMap<>();
            for (CommunicationTag tag : tags.findAllByActiveTrueOrderBySortOrderAsc()) {
                byCategory.computeIfAbsent(tag.getCategory(), c -> new ArrayList<>()).add(tag.getCode());
            }
            update.put("tagCodes", byCategory.values().stream()
                    .map(codes -> codes.get(random.nextInt(codes.size()))).toList());
        }
        if (p.goals().isEmpty()) {
            List<String> codes = new ArrayList<>(goals.findAllByActiveTrueOrderBySortOrderAsc().stream()
                    .map(RelationshipGoal::getCode).filter(c -> !c.equals("NOT_SURE")).toList());
            Collections.shuffle(codes, random);
            update.put("goalCodes", codes.subList(0, Math.min(1 + random.nextInt(2), codes.size())));
        }
        if (p.heightCm() == null) {
            update.put("heightCm", p.gender() == Gender.MALE ? 172 + random.nextInt(20) : 158 + random.nextInt(18));
        }
        if (!p.showZodiac() && p.birthDate() != null && random.nextInt(10) < 7) {
            update.put("showZodiac", true);
        }
        if (p.dailyStatusText() == null && random.nextInt(10) < 4) {
            update.put("dailyStatusText", STATUSES.get(random.nextInt(STATUSES.size())));
        }
        Integer cityId = p.cityId();
        if (cityId == null && p.city() != null) {
            cityId = cities.resolve(p.city()).map(CityRepository.City::id).orElse(null);
            if (cityId != null) {
                update.put("cityId", cityId);
            }
        }
        if (!update.isEmpty()) {
            profileService.updateProfile(accountId, jsonMapper.convertValue(update, ProfileUpdateDto.class));
        }
        // Distances in the feed: a point a few kilometres from the city centre.
        if (!p.locationShared() && cityId != null) {
            cities.findById(cityId).ifPresent(city -> profileService.updateLocation(accountId,
                    city.latitude() + (random.nextDouble() - 0.5) * 0.12,
                    city.longitude() + (random.nextDouble() - 0.5) * 0.2));
        }
        if (!p.verified() && random.nextInt(10) < 7) {
            verification.setVerified(accountId, true);
        }
        return profileService.getMyProfile(accountId);
    }

    private static void putIfMissing(Map<String, Object> update, String field, String current, String value) {
        if (current == null) {
            update.put(field, value);
        }
    }

    private static String pick(Map<String, String> options, Random random) {
        List<String> codes = List.copyOf(options.keySet());
        return codes.get(random.nextInt(codes.size()));
    }
}
