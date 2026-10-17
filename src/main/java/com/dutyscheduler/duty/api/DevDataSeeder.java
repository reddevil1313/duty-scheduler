package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.persistence.AppUserEntity;
import com.dutyscheduler.duty.persistence.AppUserRepository;
import com.dutyscheduler.duty.persistence.TeamEntity;
import com.dutyscheduler.duty.persistence.TeamRepository;
import com.dutyscheduler.duty.persistence.TeamScope;
import com.dutyscheduler.duty.persistence.TrooperEntity;
import com.dutyscheduler.duty.persistence.TrooperRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

/**
 * Dev profile only: a team, two accounts and a nine-Trooper roster. Not a
 * migration, so it never runs in production.
 *
 * <p>
 * Insert-only: existing accounts are left alone, and the roster is only
 * seeded when empty, so removed Troopers stay removed.
 */
@Configuration
@Profile("dev")
public class DevDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    /**
     * Enough for every day of the week (see {@code DevDataSeederIT}). Weekends
     * are tightest since stay-outs are away. A-C are the night group.
     */
    static final List<SeedTrooper> ROSTER = List.of(
            new SeedTrooper("TROOPER_A", false, true),
            new SeedTrooper("TROOPER_B", false, true),
            new SeedTrooper("TROOPER_C", false, true),
            new SeedTrooper("TROOPER_D", false, false),
            new SeedTrooper("TROOPER_E", false, false),
            new SeedTrooper("TROOPER_F", false, false),
            new SeedTrooper("TROOPER_G", false, false),
            new SeedTrooper("TROOPER_H", true, false),
            new SeedTrooper("TROOPER_I", true, false));

    record SeedTrooper(String name, boolean stayOut, boolean onNight) {
    }

    static final String TEAM = "Dev team";

    @Bean
    ApplicationRunner seedDevData(AppUserRepository users, TeamRepository teams, TrooperRepository troopers,
            PasswordEncoder encoder,
            @Value("${dev.seed.admin-password}") String adminPassword,
            @Value("${dev.seed.trooper-password}") String trooperPassword) {
        return args -> {
            // Use the dev admin's existing team if there is one (V6 put old data
            // in "First team").
            long team = users.findByUsername("admin")
                    .map(AppUserEntity::getTeamId)
                    .orElseGet(() -> teams.findByNameIgnoreCase(TEAM)
                            .orElseGet(() -> teams.save(new TeamEntity(TEAM)))
                            .getId());
            seedAccount(users, encoder, "admin", "Admin", adminPassword, "ADMIN", team);
            seedAccount(users, encoder, "trooper_d", "Trooper D", trooperPassword, "USER", team);
            // Nobody is signed in at startup.
            TeamScope.run(team, () -> seedRoster(troopers));
        };
    }

    private static void seedAccount(AppUserRepository users, PasswordEncoder encoder,
            String username, String displayName, String password, String role, long team) {
        if (users.findByUsername(username).isPresent()) {
            return;
        }
        users.save(new AppUserEntity(username, displayName, encoder.encode(password), role, team));
        log.info("Seeded dev account '{}' ({})", username, role);
    }

    private static void seedRoster(TrooperRepository troopers) {
        if (troopers.count() > 0) {
            return;
        }
        for (SeedTrooper seed : ROSTER) {
            TrooperEntity entity = new TrooperEntity(seed.name(), seed.stayOut());
            entity.setOnNight(seed.onNight());
            troopers.save(entity);
        }
        log.info("Seeded dev roster of {} Troopers", ROSTER.size());
    }
}
