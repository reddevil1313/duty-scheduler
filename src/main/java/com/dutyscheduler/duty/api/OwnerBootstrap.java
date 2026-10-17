package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.persistence.AppUserEntity;
import com.dutyscheduler.duty.persistence.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The owner, made at startup from {@code OWNER_USERNAME} and
 * {@code OWNER_PASSWORD} — in every environment, because without an owner
 * nobody can make a team and so nobody can ever sign in.
 *
 * <p>
 * From the environment rather than a migration, for the reason the dev seeder
 * gives: a migration would put a password hash in a public repo. And not
 * "whoever signs in first on an empty database", which hands a fresh deploy to
 * whoever reaches it first.
 *
 * <p>
 * Insert-only, like the dev seeder: once there is an owner this does nothing,
 * so the variables can be left set without resetting a changed password at
 * every restart, or removed once the account exists.
 */
@Configuration
public class OwnerBootstrap {

    private static final Logger log = LoggerFactory.getLogger(OwnerBootstrap.class);

    @Bean
    ApplicationRunner bootstrapOwner(AppUserRepository users, PasswordEncoder encoder,
            @Value("${owner.bootstrap.username:}") String username,
            @Value("${owner.bootstrap.password:}") String password) {
        return args -> {
            if (users.existsByRole("OWNER")) {
                return;
            }
            if (username.isBlank() || password.isBlank()) {
                log.warn("There is no owner account, so no team can be created. "
                        + "Set OWNER_USERNAME and OWNER_PASSWORD and restart to make one.");
                return;
            }
            if (users.findByUsername(username).isPresent()) {
                log.error("Cannot make '{}' the owner: that username is already a team's account.", username);
                return;
            }
            users.save(new AppUserEntity(username, "Owner", encoder.encode(password), "OWNER", null));
            log.info("Created the owner account '{}'", username);
        };
    }
}
