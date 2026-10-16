package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.persistence.AppUserEntity;
import com.dutyscheduler.duty.persistence.AppUserRepository;
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
 * Enough data that a fresh development database can be signed into and
 * scheduled: two accounts and a nine-Trooper roster.
 *
 * <p>
 * A runner under the {@code dev} profile rather than a Flyway migration. A
 * migration runs everywhere Flyway does, production included, and would put a
 * known password hash in a public repo. Here the hash is made at startup by the
 * same {@link PasswordEncoder} that checks it, so the two cannot drift.
 *
 * <p>
 * It only ever inserts. An account that already exists is left alone, so a
 * password changed by hand survives a restart instead of being reset under you.
 * The roster is seeded only while it is empty — per name would bring back a
 * Trooper you had deliberately removed, every time the server started.
 */
@Configuration
@Profile("dev")
public class DevDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    /**
     * Enough to cover every day of the week, which {@code DevDataSeederIT}
     * proves by generating all seven. The weekend is the tight part: stay-outs
     * are not available at all then, so the seven who live in carry Sunday's 51
     * Trooper-hours on their own. A, B and C are the default night group —
     * three is what the silent hours need.
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

    @Bean
    ApplicationRunner seedDevData(AppUserRepository users, TrooperRepository troopers,
            PasswordEncoder encoder,
            @Value("${dev.seed.admin-password}") String adminPassword,
            @Value("${dev.seed.trooper-password}") String trooperPassword) {
        return args -> {
            seedAccount(users, encoder, "admin", "Admin", adminPassword, "ADMIN");
            seedAccount(users, encoder, "trooper_d", "Trooper D", trooperPassword, "USER");
            seedRoster(troopers);
        };
    }

    private static void seedAccount(AppUserRepository users, PasswordEncoder encoder,
            String username, String displayName, String password, String role) {
        if (users.findByUsername(username).isPresent()) {
            return;
        }
        users.save(new AppUserEntity(username, displayName, encoder.encode(password), role));
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
