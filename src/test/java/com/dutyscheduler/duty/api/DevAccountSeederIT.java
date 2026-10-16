package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.persistence.AppUserEntity;
import com.dutyscheduler.duty.persistence.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The seeded accounts are only worth having if they sign in through the real
 * login filter — a row with a hash the encoder cannot match would look seeded
 * and still lock everyone out.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class DevAccountSeederIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    AppUserRepository users;

    @Autowired
    ApplicationRunner seedDevAccounts;

    @Test
    @DisplayName("both dev accounts sign in with their default passwords")
    void seededAccountsSignIn() throws Exception {
        mvc.perform(post("/api/session/login").with(csrf())
                .param("username", "admin").param("password", "admin"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/session/login").with(csrf())
                .param("username", "trooper_d").param("password", "trooper_d"))
                .andExpect(status().isNoContent());

        assertThat(users.findByUsername("admin")).map(AppUserEntity::getRole).hasValue("ADMIN");
        assertThat(users.findByUsername("trooper_d")).map(AppUserEntity::getRole).hasValue("USER");
    }

    @Test
    @DisplayName("a restart neither duplicates an account nor resets a changed password")
    void seedingAgainChangesNothing() throws Exception {
        AppUserEntity admin = users.findByUsername("admin").orElseThrow();
        String original = admin.getPasswordHash();
        admin.setPasswordHash("changed-by-hand");
        users.save(admin);
        long before = users.count();
        try {
            seedDevAccounts.run(new DefaultApplicationArguments());

            assertThat(users.count()).isEqualTo(before);
            assertThat(users.findByUsername("admin")).map(AppUserEntity::getPasswordHash)
                    .hasValue("changed-by-hand");
        } finally {
            // The container is shared with the sign-in test, which runs in no
            // fixed order relative to this one.
            admin.setPasswordHash(original);
            users.save(admin);
        }
    }
}
