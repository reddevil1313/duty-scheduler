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
 * Tells Hibernate's {@code @TenantId} which team the current session belongs
 * to, so every query is filtered by team without each repository having to
 * remember it.
 */
@Configuration
public class TenancyConfig {

    /** Matches no team, so reads are empty and inserts fail the foreign key. */
    static final Long NO_TEAM = 0L;

    @Bean
    HibernatePropertiesCustomizer teamTenancy() {
        return properties -> properties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, new TeamResolver());
    }

    static final class TeamResolver implements CurrentTenantIdentifierResolver<Long> {

        @Override
        public Long resolveCurrentTenantIdentifier() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            // A signed-in account always gets its own team (NO_TEAM for the
            // owner), never the thread's scope.
            if (authentication != null && authentication.getPrincipal() instanceof Account account) {
                return account.teamId() != null ? account.teamId() : NO_TEAM;
            }
            return TeamScope.current().orElse(NO_TEAM);
        }

        /** Not used by Spring's transactions. */
        @Override
        public boolean validateExistingCurrentSessions() {
            return false;
        }
    }
}
