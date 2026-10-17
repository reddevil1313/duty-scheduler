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
 * Creates the owner at startup from {@code OWNER_USERNAME} and
 * {@code OWNER_PASSWORD}. Without an owner nobody can make a team.
 * Insert-only: once an owner exists this does nothing, so a changed password
 * is never reset.
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
