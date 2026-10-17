package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.persistence.TeamScope;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Which team's rows a database session sees.
 *
 * <p>
 * Hibernate's {@code @TenantId} on the team-owned entities does the
 * filtering: every query gets {@code team_id = ?} added, and every insert has
 * the column filled in. Here is only where the team comes from. Done this way
 * rather than with a team parameter on each repository method, because then
 * the one query someone forgets to scope is the one that shows a team another
 * team's roster — the same reason authorization lives on the methods rather
 * than in URL rules.
 *
 * <p>
 * Asked once per session, which with open-in-view off means once per
 * transaction, so a request always gets the team of whoever made it.
 */
@Configuration
public class TenancyConfig {

    /**
     * A team id that no team has (identities start at 1). Anyone without a
     * team — the owner, someone anonymous, a thread nobody scoped — reads
     * nothing, and anything they try to insert fails V6's foreign key.
     */
    static final Long NO_TEAM = 0L;

    @Bean
    HibernatePropertiesCustomizer teamTenancy() {
        return properties -> properties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, new TeamResolver());
    }

    static final class TeamResolver implements CurrentTenantIdentifierResolver<Long> {

        @Override
        public Long resolveCurrentTenantIdentifier() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            // A signed-in account's own team, and nothing else, even when a scope
            // is open on the thread: the owner, who has none, gets NO_TEAM here
            // rather than falling through to whatever the thread was doing.
            if (authentication != null && authentication.getPrincipal() instanceof Account account) {
                return account.teamId() != null ? account.teamId() : NO_TEAM;
            }
            return TeamScope.current().orElse(NO_TEAM);
        }

        /**
         * Only consulted for Hibernate's own current-session handling, which
         * Spring does not use; its transactions open their own sessions.
         */
        @Override
        public boolean validateExistingCurrentSessions() {
            return false;
        }
    }
}
