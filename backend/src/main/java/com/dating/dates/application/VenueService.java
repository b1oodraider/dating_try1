package com.dating.dates.application;

import com.dating.accounts.application.AccountService;
import com.dating.admin.application.AdminAuditLog;
import com.dating.common.errors.ApiException;
import com.dating.common.security.Role;
import com.dating.common.security.RoleAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Places to suggest for a date (docs/date-plan.md). Curated by CONTENT_EDITOR / ADMIN; a sponsored place is an
 * advertisement and is always shown with its label data (38-FZ: «Реклама», the advertiser, the ERID).
 * Test-only places (created by the testing API) are visible to test accounts only, like seed profiles.
 */
@Service
public class VenueService {

    public static final Set<String> CATEGORIES = Set.of("COFFEE", "WALK", "FOOD", "CINEMA", "CULTURE", "ACTIVE", "OTHER");

    /** Radius of «near me»: about 30 km either way. */
    private static final double NEAR_DEGREES = 0.27;

    private static final Role[] EDITORS = {Role.CONTENT_EDITOR, Role.ADMIN};
    private static final String ENTITY = "VENUE";

    /** Advertising label; null for a place that is not sponsored. */
    public record Ad(String label, String advertiser, String erid) {
    }

    public record Venue(UUID id, String name, String address, double latitude, double longitude, String category,
                        String description, Ad ad) {
    }

    public record AdminVenue(UUID id, String name, String address, double latitude, double longitude, String category,
                             String description, boolean sponsored, String advertiser, String erid, boolean testOnly,
                             boolean active) {
    }

    public record Change(String name, String address, double latitude, double longitude, String category,
                         String description, boolean sponsored, String advertiser, String erid, boolean active) {
    }

    private final JdbcClient jdbc;
    private final AccountService accountService;
    private final RoleAccess roleAccess;
    private final AdminAuditLog auditLog;

    public VenueService(JdbcClient jdbc, AccountService accountService, RoleAccess roleAccess, AdminAuditLog auditLog) {
        this.jdbc = jdbc;
        this.accountService = accountService;
        this.roleAccess = roleAccess;
        this.auditLog = auditLog;
    }

    /** Active places around a point, sponsored first, then by distance; at most 50. */
    @Transactional(readOnly = true)
    public List<Venue> near(UUID viewer, double latitude, double longitude) {
        return jdbc.sql("""
                        SELECT * FROM venues
                         WHERE active AND test_only = :test
                           AND latitude BETWEEN :lat - :d AND :lat + :d
                           AND longitude BETWEEN :lng - :d * 2 AND :lng + :d * 2
                         ORDER BY sponsored DESC,
                                  (latitude - :lat) * (latitude - :lat) + (longitude - :lng) * (longitude - :lng)
                         LIMIT 50
                        """)
                .param("test", accountService.isTestAccount(viewer))
                .param("lat", latitude).param("lng", longitude).param("d", NEAR_DEGREES)
                .query(VenueService::venue).list();
    }

    @Transactional(readOnly = true)
    public Optional<Venue> find(UUID id) {
        return jdbc.sql("SELECT * FROM venues WHERE id = :id AND active").param("id", id)
                .query(VenueService::venue).optional();
    }

    // ------------------------------------------------------------- staff

    @Transactional(readOnly = true)
    public List<AdminVenue> all(UUID actor) {
        roleAccess.require(actor, EDITORS);
        return jdbc.sql("SELECT * FROM venues ORDER BY test_only, name").query(VenueService::adminVenue).list();
    }

    @Transactional
    public AdminVenue create(UUID actor, Change change) {
        roleAccess.require(actor, EDITORS);
        AdminVenue created = insert(change, false);
        auditLog.record(actor, "CREATE", ENTITY, created.id().toString(), null, created);
        return created;
    }

    @Transactional
    public AdminVenue update(UUID actor, UUID id, Change change) {
        roleAccess.require(actor, EDITORS);
        AdminVenue before = adminFind(id);
        validate(change);
        jdbc.sql("""
                        UPDATE venues SET name = :name, address = :address, latitude = :lat, longitude = :lng,
                               category = :category, description = :description, sponsored = :sponsored,
                               advertiser = :advertiser, erid = :erid, active = :active, updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", id).param("name", change.name()).param("address", change.address())
                .param("lat", change.latitude()).param("lng", change.longitude()).param("category", change.category())
                .param("description", change.description()).param("sponsored", change.sponsored())
                .param("advertiser", change.advertiser()).param("erid", change.erid()).param("active", change.active())
                .update();
        AdminVenue after = adminFind(id);
        auditLog.record(actor, "UPDATE", ENTITY, id.toString(), before, after);
        return after;
    }

    /** Testing API: three test-only places around a point, one of them sponsored (to see the ad label). */
    @Transactional
    public List<AdminVenue> createSamples(double latitude, double longitude) {
        return List.of(
                insert(new Change("Кофейня «Тестовая»", "Тестовая ул., 1", latitude + 0.004, longitude + 0.006,
                        "COFFEE", "Тихое место для первого разговора", true, "ООО «Тест-реклама»", "TEST-ERID-1", true),
                        true),
                insert(new Change("Тестовый парк", null, latitude - 0.006, longitude - 0.004, "WALK",
                        "Аллеи и пруд", false, null, null, true), true),
                insert(new Change("Тестовый кинотеатр", "Проспект Тестов, 10", latitude + 0.002, longitude - 0.008,
                        "CINEMA", null, false, null, null, true), true));
    }

    private AdminVenue insert(Change change, boolean testOnly) {
        validate(change);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO venues (id, name, address, latitude, longitude, category, description, sponsored,
                                            advertiser, erid, test_only, active)
                        VALUES (:id, :name, :address, :lat, :lng, :category, :description, :sponsored, :advertiser,
                                :erid, :test, :active)
                        """)
                .param("id", id).param("name", change.name()).param("address", change.address())
                .param("lat", change.latitude()).param("lng", change.longitude()).param("category", change.category())
                .param("description", change.description()).param("sponsored", change.sponsored())
                .param("advertiser", change.advertiser()).param("erid", change.erid()).param("test", testOnly)
                .param("active", change.active())
                .update();
        return adminFind(id);
    }

    private static void validate(Change change) {
        if (!CATEGORIES.contains(change.category())) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Неизвестная категория места");
        }
        if (change.sponsored() && (change.advertiser() == null || change.advertiser().isBlank())) {
            throw ApiException.badRequest("VALIDATION_ERROR", "У рекламного места должен быть указан рекламодатель");
        }
    }

    private AdminVenue adminFind(UUID id) {
        return jdbc.sql("SELECT * FROM venues WHERE id = :id").param("id", id).query(VenueService::adminVenue)
                .optional()
                .orElseThrow(() -> new ApiException("VENUE_NOT_FOUND", "Место не найдено", HttpStatus.NOT_FOUND));
    }

    private static Venue venue(ResultSet rs, int row) throws SQLException {
        Ad ad = rs.getBoolean("sponsored")
                ? new Ad("Реклама", rs.getString("advertiser"), rs.getString("erid"))
                : null;
        return new Venue(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("address"),
                rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getString("category"),
                rs.getString("description"), ad);
    }

    private static AdminVenue adminVenue(ResultSet rs, int row) throws SQLException {
        return new AdminVenue(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("address"),
                rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getString("category"),
                rs.getString("description"), rs.getBoolean("sponsored"), rs.getString("advertiser"),
                rs.getString("erid"), rs.getBoolean("test_only"), rs.getBoolean("active"));
    }
}
