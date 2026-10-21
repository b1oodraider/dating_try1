package com.dating.dates.application;

import com.dating.chat.application.ChatService;
import com.dating.common.entitlements.FeatureAccess;
import com.dating.common.entitlements.PremiumFeatures;
import com.dating.common.errors.ApiException;
import com.dating.safety.application.RestrictionGuard;
import com.dating.safety.domain.RestrictionKind;
import com.dating.social.application.MatchService;
import com.dating.social.domain.MatchInfo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Date plan in a chat (docs/date-plan.md). A premium user proposes 1..5 points; the other side accepts or
 * declines (free); a premium participant may edit an open plan, which turns it into a counter proposal the other
 * side answers. Either side may cancel. One plan in play (PROPOSED or ACCEPTED) per match. Every action is kept
 * in the history and posted to the chat as a DATE_PLAN message.
 */
@Service
public class DatePlanService {

    public static final int MAX_ITEMS = 5;
    static final Duration MAX_AHEAD = Duration.ofDays(120);

    public record ItemInput(String placeName, String address, Double latitude, Double longitude, Instant startsAt,
                            String activity, String comment, UUID venueId) {
    }

    public record Item(String placeName, String address, double latitude, double longitude, Instant startsAt,
                       String activity, String comment, VenueService.Venue venue) {
    }

    /**
     * @param mine        I proposed it
     * @param canRespond  I may accept or decline (it is PROPOSED and the last change is the other person's)
     * @param canEdit     it is open and I have the feature (premium)
     */
    public record Plan(UUID id, UUID matchId, String status, int version, boolean mine, boolean lastEditMine,
                       boolean canRespond, boolean canEdit, List<Item> items, Instant createdAt, Instant updatedAt) {
    }

    public record HistoryEntry(int version, boolean mine, String action, List<Map<String, Object>> items,
                               Instant at) {
    }

    private record Row(UUID id, UUID matchId, UUID proposer, UUID lastEditor, String status, int version,
                       Instant createdAt, Instant updatedAt) {
    }

    private final JdbcClient jdbc;
    private final MatchService matchService;
    private final ChatService chatService;
    private final FeatureAccess featureAccess;
    private final RestrictionGuard restrictionGuard;
    private final VenueService venues;
    private final JsonMapper jsonMapper;

    public DatePlanService(JdbcClient jdbc, MatchService matchService, ChatService chatService,
                           FeatureAccess featureAccess, RestrictionGuard restrictionGuard, VenueService venues,
                           JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.matchService = matchService;
        this.chatService = chatService;
        this.featureAccess = featureAccess;
        this.restrictionGuard = restrictionGuard;
        this.venues = venues;
        this.jsonMapper = jsonMapper;
    }

    /** The plan in play in this chat (proposed or agreed), if any. */
    @Transactional(readOnly = true)
    public Optional<Plan> current(UUID me, UUID matchId) {
        matchService.requireActive(matchId, me);
        return jdbc.sql("SELECT * FROM date_plans WHERE match_id = :m AND status IN ('PROPOSED', 'ACCEPTED')")
                .param("m", matchId).query(DatePlanService::row).optional().map(r -> view(me, r));
    }

    /** Any plan of my chats (an older card in the chat shows its current state). */
    @Transactional(readOnly = true)
    public Plan get(UUID me, UUID planId) {
        return view(me, requireMine(me, planId));
    }

    @Transactional
    public Plan propose(UUID me, UUID matchId, List<ItemInput> input) {
        restrictionGuard.requireAllowed(me, RestrictionKind.MESSAGES);
        featureAccess.require(me, PremiumFeatures.DATE_PLAN);
        matchService.requireActive(matchId, me);
        List<Item> items = validate(input);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                            INSERT INTO date_plans (id, match_id, proposer_account_id, last_editor_account_id, status)
                            VALUES (:id, :m, :me, :me, 'PROPOSED')
                            """)
                    .param("id", id).param("m", matchId).param("me", me).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException("PLAN_ALREADY_OPEN", "В этом чате уже есть план встречи — измените или отмените его",
                    HttpStatus.CONFLICT);
        }
        saveItems(id, items);
        history(id, 1, me, "PROPOSE", items);
        chatService.postDatePlanMessage(me, matchId, id, "Предлагаю встречу: " + summary(items));
        return view(me, require(id));
    }

    /** A counter proposal (or my own correction): the other side answers it again. */
    @Transactional
    public Plan edit(UUID me, UUID planId, int expectedVersion, List<ItemInput> input) {
        restrictionGuard.requireAllowed(me, RestrictionKind.MESSAGES);
        featureAccess.require(me, PremiumFeatures.DATE_PLAN);
        Row plan = lockOpen(me, planId);
        if (plan.version() != expectedVersion) {
            throw new ApiException("PLAN_CHANGED", "План уже изменили — обновите его", HttpStatus.CONFLICT);
        }
        List<Item> items = validate(input);
        jdbc.sql("""
                        UPDATE date_plans SET status = 'PROPOSED', version = version + 1, last_editor_account_id = :me,
                               updated_at = now()
                         WHERE id = :id
                        """).param("id", planId).param("me", me).update();
        jdbc.sql("DELETE FROM date_plan_items WHERE plan_id = :id").param("id", planId).update();
        saveItems(planId, items);
        history(planId, plan.version() + 1, me, "EDIT", items);
        chatService.postDatePlanMessage(me, plan.matchId(), planId, "Изменил(а) план: " + summary(items));
        return view(me, require(planId));
    }

    @Transactional
    public Plan accept(UUID me, UUID planId) {
        return respond(me, planId, "ACCEPTED", "ACCEPT", "Принял(а) план встречи");
    }

    @Transactional
    public Plan decline(UUID me, UUID planId) {
        return respond(me, planId, "DECLINED", "DECLINE", "Отклонил(а) план встречи");
    }

    @Transactional
    public Plan cancel(UUID me, UUID planId) {
        Row plan = lockOpen(me, planId);
        setStatus(planId, "CANCELLED");
        history(planId, plan.version(), me, "CANCEL", items(planId));
        chatService.postDatePlanMessage(me, plan.matchId(), planId, "Отменил(а) план встречи");
        return view(me, require(planId));
    }

    @Transactional(readOnly = true)
    public List<HistoryEntry> history(UUID me, UUID planId) {
        requireMine(me, planId);
        return jdbc.sql("""
                        SELECT version, editor_account_id, action, items, created_at FROM date_plan_history
                         WHERE plan_id = :id ORDER BY created_at, version
                        """)
                .param("id", planId)
                .query((rs, n) -> new HistoryEntry(rs.getInt("version"),
                        me.equals(rs.getObject("editor_account_id", UUID.class)), rs.getString("action"),
                        readItems(rs.getString("items")), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    // ------------------------------------------------------------- internals

    private Plan respond(UUID me, UUID planId, String status, String action, String text) {
        Row plan = lockOpen(me, planId);
        if (!plan.status().equals("PROPOSED") || plan.lastEditor().equals(me)) {
            throw new ApiException("PLAN_NOT_YOURS_TO_ANSWER", "Ответить на план может только тот, кому его предложили",
                    HttpStatus.CONFLICT);
        }
        setStatus(planId, status);
        history(planId, plan.version(), me, action, items(planId));
        chatService.postDatePlanMessage(me, plan.matchId(), planId, text);
        return view(me, require(planId));
    }

    /** My plan in play, locked; finished plans and chats that ended cannot change. */
    private Row lockOpen(UUID me, UUID planId) {
        requireMine(me, planId);
        Row plan = jdbc.sql("SELECT * FROM date_plans WHERE id = :id FOR UPDATE").param("id", planId)
                .query(DatePlanService::row).single();
        if (!plan.status().equals("PROPOSED") && !plan.status().equals("ACCEPTED")) {
            throw new ApiException("PLAN_CLOSED", "План уже закрыт", HttpStatus.CONFLICT);
        }
        matchService.requireActive(plan.matchId(), me);
        return plan;
    }

    private Row requireMine(UUID me, UUID planId) {
        Row plan = require(planId);
        if (!matchService.isParticipant(plan.matchId(), me)) {
            throw notFound();
        }
        return plan;
    }

    private Row require(UUID planId) {
        return jdbc.sql("SELECT * FROM date_plans WHERE id = :id").param("id", planId)
                .query(DatePlanService::row).optional().orElseThrow(DatePlanService::notFound);
    }

    private static ApiException notFound() {
        return new ApiException("PLAN_NOT_FOUND", "План встречи не найден", HttpStatus.NOT_FOUND);
    }

    private void setStatus(UUID planId, String status) {
        jdbc.sql("UPDATE date_plans SET status = :s, updated_at = now() WHERE id = :id")
                .param("s", status).param("id", planId).update();
    }

    private List<Item> validate(List<ItemInput> input) {
        if (input == null || input.isEmpty() || input.size() > MAX_ITEMS) {
            throw ApiException.badRequest("VALIDATION_ERROR", "В плане от 1 до " + MAX_ITEMS + " пунктов");
        }
        Instant now = Instant.now();
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            ItemInput in = input.get(i);
            String field = "items[" + i + "].";
            VenueService.Venue venue = in.venueId() == null ? null : venues.find(in.venueId())
                    .orElseThrow(() -> invalid(field + "venueId", "Место не найдено"));
            String name = venue != null && blank(in.placeName()) ? venue.name() : trim(in.placeName());
            double lat = venue != null && in.latitude() == null ? venue.latitude() : orInvalid(in.latitude(), field + "latitude");
            double lng = venue != null && in.longitude() == null ? venue.longitude() : orInvalid(in.longitude(), field + "longitude");
            if (blank(name) || name.length() > 120) {
                throw invalid(field + "placeName", "Название места — от 1 до 120 символов");
            }
            if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                throw invalid(field + "latitude", "Некорректные координаты");
            }
            if (in.startsAt() == null || !in.startsAt().isAfter(now) || in.startsAt().isAfter(now.plus(MAX_AHEAD))) {
                throw invalid(field + "startsAt", "Время встречи — в будущем, не дальше 120 дней");
            }
            String activity = in.activity() == null && venue != null ? venue.category() : in.activity();
            if (activity == null || !VenueService.CATEGORIES.contains(activity)) {
                throw invalid(field + "activity", "Выберите, чем заняться");
            }
            String comment = trim(in.comment());
            if (comment != null && comment.length() > 300) {
                throw invalid(field + "comment", "Комментарий не длиннее 300 символов");
            }
            String address = venue != null && blank(in.address()) ? venue.address() : trim(in.address());
            if (address != null && address.length() > 200) {
                throw invalid(field + "address", "Адрес не длиннее 200 символов");
            }
            items.add(new Item(name, blank(address) ? null : address, lat, lng, in.startsAt(), activity,
                    blank(comment) ? null : comment, venue));
        }
        return items;
    }

    private void saveItems(UUID planId, List<Item> items) {
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            jdbc.sql("""
                            INSERT INTO date_plan_items (plan_id, position, place_name, address, latitude, longitude,
                                                         starts_at, activity, comment, venue_id)
                            VALUES (:plan, :pos, :name, :address, :lat, :lng, :at, :activity, :comment, :venue)
                            """)
                    .param("plan", planId).param("pos", i).param("name", item.placeName())
                    .param("address", item.address()).param("lat", item.latitude()).param("lng", item.longitude())
                    .param("at", OffsetDateTime.ofInstant(item.startsAt(), java.time.ZoneOffset.UTC))
                    .param("activity", item.activity()).param("comment", item.comment())
                    .param("venue", item.venue() == null ? null : item.venue().id())
                    .update();
        }
    }

    private List<Item> items(UUID planId) {
        return jdbc.sql("SELECT * FROM date_plan_items WHERE plan_id = :id ORDER BY position").param("id", planId)
                .query((rs, n) -> {
                    UUID venueId = rs.getObject("venue_id", UUID.class);
                    return new Item(rs.getString("place_name"), rs.getString("address"), rs.getDouble("latitude"),
                            rs.getDouble("longitude"), rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                            rs.getString("activity"), rs.getString("comment"),
                            venueId == null ? null : venues.find(venueId).orElse(null));
                })
                .list();
    }

    private void history(UUID planId, int version, UUID editor, String action, List<Item> items) {
        List<Map<String, Object>> snapshot = items.stream().map(item -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("placeName", item.placeName());
            m.put("address", item.address());
            m.put("latitude", item.latitude());
            m.put("longitude", item.longitude());
            m.put("startsAt", item.startsAt().toString());
            m.put("activity", item.activity());
            m.put("comment", item.comment());
            m.put("venueId", item.venue() == null ? null : item.venue().id().toString());
            return m;
        }).toList();
        jdbc.sql("""
                        INSERT INTO date_plan_history (id, plan_id, version, editor_account_id, action, items)
                        VALUES (:id, :plan, :version, :editor, :action, CAST(:items AS jsonb))
                        """)
                .param("id", UUID.randomUUID()).param("plan", planId).param("version", version)
                .param("editor", editor).param("action", action)
                .param("items", jsonMapper.writeValueAsString(snapshot))
                .update();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readItems(String json) {
        return jsonMapper.readValue(json, List.class);
    }

    private Plan view(UUID me, Row plan) {
        boolean open = plan.status().equals("PROPOSED") || plan.status().equals("ACCEPTED");
        return new Plan(plan.id(), plan.matchId(), plan.status(), plan.version(), plan.proposer().equals(me),
                plan.lastEditor().equals(me), plan.status().equals("PROPOSED") && !plan.lastEditor().equals(me),
                open && featureAccess.isAllowed(me, PremiumFeatures.DATE_PLAN), items(plan.id()),
                plan.createdAt(), plan.updatedAt());
    }

    /** «Кофейня «Зёрна», 25.10 в 19:00 и ещё 1» — the line for the chat list and pushes. */
    private static String summary(List<Item> items) {
        Item first = items.getFirst();
        var at = first.startsAt().atZone(java.time.ZoneId.of("Europe/Moscow"));
        String text = String.format("%s, %02d.%02d в %02d:%02d", first.placeName(), at.getDayOfMonth(),
                at.getMonthValue(), at.getHour(), at.getMinute());
        return items.size() > 1 ? text + " и ещё " + (items.size() - 1) : text;
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("match_id", UUID.class),
                rs.getObject("proposer_account_id", UUID.class), rs.getObject("last_editor_account_id", UUID.class),
                rs.getString("status"), rs.getInt("version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static ApiException invalid(String field, String message) {
        return ApiException.validation("VALIDATION_ERROR", message, Map.of(field, message));
    }

    private static double orInvalid(Double value, String field) {
        if (value == null) {
            throw invalid(field, "Выберите место на карте");
        }
        return value;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
