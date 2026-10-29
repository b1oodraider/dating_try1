package com.dating.communities.application;

import com.dating.accounts.application.AccountDeletedEvent;
import com.dating.admin.application.AdminAuditLog;
import com.dating.common.errors.ApiException;
import com.dating.common.ratelimit.FixedWindowRateLimiter;
import com.dating.common.security.Role;
import com.dating.common.security.RoleAccess;
import com.dating.communities.api.CommunityRefDto;
import com.dating.safety.application.ContentPolicy;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Interest groups (docs/communities.md): a community around a film, book, series, game or franchise. Staff
 * (CONTENT_EDITOR / ADMIN) create them; people subscribe, suggest new ones, and search the feed among members.
 */
@Service
public class CommunityService {

    /** Kind → label, in display order. */
    public static final Map<String, String> KINDS = ordered("FRANCHISE", "Вселенные", "FILM", "Фильмы",
            "SERIES", "Сериалы", "BOOK", "Книги", "ANIME", "Аниме", "GAME", "Игры", "MUSIC", "Музыка",
            "OTHER", "Другое");

    private static final Role[] EDITORS = {Role.CONTENT_EDITOR, Role.ADMIN};
    private static final String ENTITY = "COMMUNITY";
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,59}$");
    private static final int LIST_LIMIT = 100;

    public record Community(long id, String title, String kind, String kindLabel, String emoji, String description,
                            long members, boolean subscribed) {
    }

    public record AdminCommunity(long id, String slug, String title, String kind, String emoji, String description,
                                 int sortOrder, boolean active, long members) {
    }

    public record Change(String slug, String title, String kind, String emoji, String description, Integer sortOrder,
                         Boolean active) {
    }

    public record Suggestion(UUID id, UUID accountId, String title, String comment, String status, Instant createdAt) {
    }

    private final JdbcClient jdbc;
    private final RoleAccess roleAccess;
    private final AdminAuditLog auditLog;
    // shortcut: in-memory, per instance; move to the shared limiter store with a second server.
    private final FixedWindowRateLimiter suggestionLimiter = new FixedWindowRateLimiter(5, Duration.ofDays(1));

    public CommunityService(JdbcClient jdbc, RoleAccess roleAccess, AdminAuditLog auditLog) {
        this.jdbc = jdbc;
        this.roleAccess = roleAccess;
        this.auditLog = auditLog;
    }

    // ---------------------------------------------------------------- users

    /** Active groups: by title (contains, case-insensitive) and kind; the most popular first. */
    @Transactional(readOnly = true)
    public List<Community> list(UUID viewer, String query, String kind) {
        String q = query == null ? "" : query.trim().toLowerCase();
        if (kind != null && !KINDS.containsKey(kind)) {
            throw ApiException.badRequest("UNKNOWN_KIND", "Неизвестный тип группы");
        }
        return jdbc.sql(SELECT + """
                         WHERE c.active
                           AND (:q = '' OR lower(c.title) LIKE '%' || :q || '%')
                           AND (CAST(:kind AS VARCHAR) IS NULL OR c.kind = :kind)
                         ORDER BY members DESC, c.sort_order, c.title
                         LIMIT :limit
                        """)
                .param("viewer", viewer).param("q", q).param("kind", kind).param("limit", LIST_LIMIT)
                .query(CommunityService::community).list();
    }

    @Transactional(readOnly = true)
    public Community get(UUID viewer, long id) {
        return jdbc.sql(SELECT + " WHERE c.active AND c.id = :id")
                .param("viewer", viewer).param("id", id)
                .query(CommunityService::community).optional()
                .orElseThrow(CommunityService::notFound);
    }

    /** My groups, the latest joined first. */
    @Transactional(readOnly = true)
    public List<Community> mine(UUID viewer) {
        return jdbc.sql(SELECT + """
                         JOIN community_members me ON me.community_id = c.id AND me.account_id = :viewer
                         WHERE c.active
                         ORDER BY me.joined_at DESC
                        """)
                .param("viewer", viewer).query(CommunityService::community).list();
    }

    @Transactional
    public Community subscribe(UUID viewer, long id) {
        get(viewer, id);
        jdbc.sql("INSERT INTO community_members (account_id, community_id) VALUES (:a, :c) ON CONFLICT DO NOTHING")
                .param("a", viewer).param("c", id).update();
        return get(viewer, id);
    }

    @Transactional
    public Community unsubscribe(UUID viewer, long id) {
        get(viewer, id);
        jdbc.sql("DELETE FROM community_members WHERE account_id = :a AND community_id = :c")
                .param("a", viewer).param("c", id).update();
        return get(viewer, id);
    }

    /** A user's idea for a new group; staff review it. 429 after 5 a day. */
    @Transactional
    public void suggest(UUID viewer, String title, String comment) {
        String t = title == null ? "" : title.strip();
        String c = comment == null || comment.isBlank() ? null : comment.strip();
        if (t.length() < 2 || t.length() > 80) {
            throw ApiException.validation("VALIDATION_ERROR", "Проверьте название",
                    Map.of("title", "Название — от 2 до 80 символов"));
        }
        if (c != null && c.length() > 300) {
            throw ApiException.validation("VALIDATION_ERROR", "Проверьте комментарий",
                    Map.of("comment", "Комментарий — не длиннее 300 символов"));
        }
        ContentPolicy.violation(t + " " + (c == null ? "" : c)).ifPresent(message -> {
            throw ApiException.validation("VALIDATION_ERROR", message, Map.of("title", message));
        });
        long wait = suggestionLimiter.tryAcquire(viewer.toString());
        if (wait > 0) {
            throw ApiException.tooManyRequests("Сегодня вы уже предложили несколько групп — попробуйте завтра", wait);
        }
        jdbc.sql("INSERT INTO community_suggestions (id, account_id, title, comment) VALUES (:id, :a, :t, :c)")
                .param("id", UUID.randomUUID()).param("a", viewer).param("t", t).param("c", c).update();
    }

    /**
     * Groups of these people for a profile card, shared ones (the viewer is in them too) first.
     *
     * @return account id → groups; absent when the person is in none
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<CommunityRefDto>> of(UUID viewer, Collection<UUID> accountIds) {
        Map<UUID, List<CommunityRefDto>> result = new HashMap<>();
        if (accountIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                        SELECT m.account_id, c.id, c.title, c.emoji,
                               EXISTS (SELECT 1 FROM community_members v
                                        WHERE v.community_id = c.id AND v.account_id = :viewer) AS shared
                          FROM community_members m JOIN communities c ON c.id = m.community_id
                         WHERE m.account_id IN (:ids) AND c.active
                         ORDER BY shared DESC, c.sort_order, c.title
                        """)
                .param("viewer", viewer).param("ids", accountIds)
                .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class),
                        new CommunityRefDto(rs.getLong(2), rs.getString(3), rs.getString(4), rs.getBoolean(5))))
                .list()
                .forEach(e -> result.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return result;
    }

    @EventListener
    public void onAccountDeleted(AccountDeletedEvent event) {
        jdbc.sql("DELETE FROM community_members WHERE account_id = :a").param("a", event.accountId()).update();
        jdbc.sql("DELETE FROM community_suggestions WHERE account_id = :a").param("a", event.accountId()).update();
    }

    // ---------------------------------------------------------------- staff

    @Transactional(readOnly = true)
    public List<AdminCommunity> all(UUID actor) {
        roleAccess.require(actor, EDITORS);
        return jdbc.sql(ADMIN_SELECT + " ORDER BY c.sort_order, c.title").query(CommunityService::adminCommunity).list();
    }

    @Transactional
    public AdminCommunity create(UUID actor, Change change) {
        roleAccess.require(actor, EDITORS);
        validate(change, true);
        long id = jdbc.sql("""
                        INSERT INTO communities (slug, title, kind, emoji, description, sort_order, active)
                        VALUES (:slug, :title, :kind, :emoji, :description, :sort, :active) RETURNING id
                        """)
                .param("slug", change.slug()).param("title", change.title().strip()).param("kind", change.kind())
                .param("emoji", blankToNull(change.emoji())).param("description", blankToNull(change.description()))
                .param("sort", change.sortOrder() == null ? 0 : change.sortOrder())
                .param("active", change.active() == null || change.active())
                .query(Long.class).single();
        AdminCommunity created = adminFind(id);
        auditLog.record(actor, "CREATE", ENTITY, String.valueOf(id), null, created);
        return created;
    }

    @Transactional
    public AdminCommunity update(UUID actor, long id, Change change) {
        roleAccess.require(actor, EDITORS);
        AdminCommunity before = adminFind(id);
        validate(change, false);
        jdbc.sql("""
                        UPDATE communities SET title = :title, kind = :kind, emoji = :emoji, description = :description,
                               sort_order = :sort, active = :active, updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", id).param("title", change.title().strip()).param("kind", change.kind())
                .param("emoji", blankToNull(change.emoji())).param("description", blankToNull(change.description()))
                .param("sort", change.sortOrder() == null ? before.sortOrder() : change.sortOrder())
                .param("active", change.active() == null ? before.active() : change.active())
                .update();
        AdminCommunity after = adminFind(id);
        auditLog.record(actor, "UPDATE", ENTITY, String.valueOf(id), before, after);
        return after;
    }

    /** Suggestions, the new ones first. */
    @Transactional(readOnly = true)
    public List<Suggestion> suggestions(UUID actor) {
        roleAccess.require(actor, EDITORS);
        return jdbc.sql("""
                        SELECT id, account_id, title, comment, status, created_at FROM community_suggestions
                         ORDER BY status = 'NEW' DESC, created_at DESC LIMIT 200
                        """)
                .query((rs, n) -> new Suggestion(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getObject(6, OffsetDateTime.class).toInstant()))
                .list();
    }

    @Transactional
    public void decide(UUID actor, UUID suggestionId, boolean accepted) {
        roleAccess.require(actor, EDITORS);
        int updated = jdbc.sql("""
                        UPDATE community_suggestions SET status = :status, decided_at = now()
                         WHERE id = :id AND status = 'NEW'
                        """)
                .param("id", suggestionId).param("status", accepted ? "ACCEPTED" : "REJECTED").update();
        if (updated == 0) {
            throw ApiException.notFound("SUGGESTION_NOT_FOUND", "Предложение не найдено или уже рассмотрено");
        }
        auditLog.record(actor, accepted ? "ACCEPT" : "REJECT", "COMMUNITY_SUGGESTION", suggestionId.toString(),
                null, null);
    }

    // ------------------------------------------------------------- helpers

    private static final String SELECT = """
            SELECT c.id, c.title, c.kind, c.emoji, c.description,
                   (SELECT count(*) FROM community_members m WHERE m.community_id = c.id) AS members,
                   EXISTS (SELECT 1 FROM community_members s
                            WHERE s.community_id = c.id AND s.account_id = :viewer) AS subscribed
              FROM communities c
            """;

    private static final String ADMIN_SELECT = """
            SELECT c.id, c.slug, c.title, c.kind, c.emoji, c.description, c.sort_order, c.active,
                   (SELECT count(*) FROM community_members m WHERE m.community_id = c.id) AS members
              FROM communities c
            """;

    private AdminCommunity adminFind(long id) {
        return jdbc.sql(ADMIN_SELECT + " WHERE c.id = :id").param("id", id)
                .query(CommunityService::adminCommunity).optional().orElseThrow(CommunityService::notFound);
    }

    private void validate(Change change, boolean creating) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (creating && (change.slug() == null || !SLUG.matcher(change.slug()).matches())) {
            errors.put("slug", "Латиница, цифры и дефис, 2–60 символов");
        } else if (creating && jdbc.sql("SELECT count(*) FROM communities WHERE slug = :s").param("s", change.slug())
                .query(Long.class).single() > 0) {
            errors.put("slug", "Такой адрес уже есть");
        }
        if (change.title() == null || change.title().strip().length() < 2 || change.title().strip().length() > 80) {
            errors.put("title", "Название — от 2 до 80 символов");
        }
        if (change.kind() == null || !KINDS.containsKey(change.kind())) {
            errors.put("kind", "Выберите тип");
        }
        if (change.emoji() != null && change.emoji().length() > 16) {
            errors.put("emoji", "Один смайлик");
        }
        if (change.description() != null && change.description().length() > 500) {
            errors.put("description", "Описание — не длиннее 500 символов");
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation("VALIDATION_ERROR", "Проверьте поля группы", errors);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static ApiException notFound() {
        return ApiException.notFound("COMMUNITY_NOT_FOUND", "Группа не найдена");
    }

    private static Community community(ResultSet rs, int n) throws SQLException {
        String kind = rs.getString("kind");
        return new Community(rs.getLong("id"), rs.getString("title"), kind, KINDS.get(kind), rs.getString("emoji"),
                rs.getString("description"), rs.getLong("members"), rs.getBoolean("subscribed"));
    }

    private static AdminCommunity adminCommunity(ResultSet rs, int n) throws SQLException {
        return new AdminCommunity(rs.getLong("id"), rs.getString("slug"), rs.getString("title"), rs.getString("kind"),
                rs.getString("emoji"), rs.getString("description"), rs.getInt("sort_order"), rs.getBoolean("active"),
                rs.getLong("members"));
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }
}
