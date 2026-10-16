package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.persistence.AppUserEntity;
import com.dutyscheduler.duty.persistence.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Two accounts so a fresh development database can be signed into at all.
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
 */
@Configuration
@Profile("dev")
public class DevAccountSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevAccountSeeder.class);

    @Bean
    ApplicationRunner seedDevAccounts(AppUserRepository users, PasswordEncoder encoder,
            @Value("${dev.seed.admin-password}") String adminPassword,
            @Value("${dev.seed.trooper-password}") String trooperPassword) {
        return args -> {
            seed(users, encoder, "admin", "Admin", adminPassword, "ADMIN");
            seed(users, encoder, "trooper_d", "Trooper D", trooperPassword, "USER");
        };
    }

    private static void seed(AppUserRepository users, PasswordEncoder encoder,
            String username, String displayName, String password, String role) {
        if (users.findByUsername(username).isPresent()) {
            return;
        }
        users.save(new AppUserEntity(username, displayName, encoder.encode(password), role));
        log.info("Seeded dev account '{}' ({})", username, role);
    }
}
