package com.dating.accounts.persistence;

import com.dating.common.security.Role;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Repository
public class AccountRoleRepository {

    private final JdbcClient jdbc;

    public AccountRoleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Set<Role> rolesOf(UUID accountId) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        jdbc.sql("SELECT role FROM account_roles WHERE account_id = :a")
                .param("a", accountId)
                .query(rs -> {
                    Role.parse(rs.getString(1)).ifPresent(roles::add);
                });
        return roles;
    }

    /**
     * @return true if the role was added now, false if the account already had it
     */
    public boolean grant(UUID accountId, Role role, UUID grantedBy) {
        return jdbc.sql("""
                        INSERT INTO account_roles (account_id, role, granted_by) VALUES (:a, :role, :by)
                        ON CONFLICT (account_id, role) DO NOTHING
                        """)
                .param("a", accountId)
                .param("role", role.name())
                .param("by", grantedBy)
                .update() == 1;
    }

    /**
     * @return true if the role was removed now
     */
    public boolean revoke(UUID accountId, Role role) {
        return jdbc.sql("DELETE FROM account_roles WHERE account_id = :a AND role = :role")
                .param("a", accountId)
                .param("role", role.name())
                .update() == 1;
    }

    /**
     * Holders of a role among active accounts, locked until the end of the transaction
     * (two admins revoking each other at once must not leave the system without an admin).
     */
    public List<UUID> lockActiveHolders(Role role) {
        return jdbc.sql("""
                        SELECT r.account_id FROM account_roles r JOIN accounts a ON a.id = r.account_id
                        WHERE r.role = :role AND a.status = 'ACTIVE'
                        ORDER BY r.account_id
                        FOR UPDATE OF r
                        """)
                .param("role", role.name())
                .query(UUID.class)
                .list();
    }

    /**
     * Every account that has at least one role, with its roles.
     */
    public Map<UUID, Set<Role>> allAssignments() {
        Map<UUID, Set<Role>> result = new LinkedHashMap<>();
        jdbc.sql("SELECT account_id, role FROM account_roles ORDER BY granted_at, account_id")
                .query(rs -> {
                    UUID accountId = rs.getObject(1, UUID.class);
                    Role.parse(rs.getString(2)).ifPresent(role ->
                            result.computeIfAbsent(accountId, id -> EnumSet.noneOf(Role.class)).add(role));
                });
        return result;
    }

    public void deleteAll(UUID accountId) {
        jdbc.sql("DELETE FROM account_roles WHERE account_id = :a").param("a", accountId).update();
    }
}
