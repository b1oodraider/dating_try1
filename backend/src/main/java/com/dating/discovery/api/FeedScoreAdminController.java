package com.dating.discovery.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.common.security.Role;
import com.dating.common.security.RoleAccess;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Why a profile stands where it does in somebody's feed: the parts of the SQL feed score (migration 024) for one
 * viewer and one candidate profile. Moderators and admins; read-only.
 */
@RestController
@RequestMapping("/api/v1/admin/discovery")
public class FeedScoreAdminController {

    private final JdbcClient jdbc;
    private final RoleAccess roleAccess;

    public FeedScoreAdminController(JdbcClient jdbc, RoleAccess roleAccess) {
        this.jdbc = jdbc;
        this.roleAccess = roleAccess;
    }

    /** @return each part, then "total" (the value the feed sorts by) */
    @GetMapping("/score")
    public Map<String, Double> score(@AuthenticationPrincipal AuthenticatedUser user,
                                     @RequestParam UUID viewerAccountId, @RequestParam UUID candidateProfileId) {
        roleAccess.require(user.accountId(), Role.MODERATOR, Role.ADMIN);
        return jdbc.sql("SELECT * FROM feed_score_parts(:viewer, :candidate)")
                .param("viewer", viewerAccountId)
                .param("candidate", candidateProfileId)
                .query(rs -> {
                    Map<String, Double> parts = new LinkedHashMap<>();
                    if (!rs.next()) {
                        return parts;
                    }
                    double total = 0;
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        double value = rs.getDouble(i);
                        parts.put(rs.getMetaData().getColumnName(i), value);
                        total += value;
                    }
                    parts.put("total", total);
                    return parts;
                });
    }
}
