package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.TestTeamListener;
import com.dutyscheduler.duty.persistence.AppUserEntity;
import com.dutyscheduler.duty.persistence.AppUserRepository;
import com.dutyscheduler.duty.persistence.TeamRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sign-in throttle, headers and actuator access. Each throttle test uses its
 * own address since the counts are shared across the context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SecurityHardeningIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    AppUserRepository users;

    @Autowired
    TeamRepository teams;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    MeterRegistry registry;

    static final String PASSWORD = "the-right-password";

    @BeforeEach
    void anAccount() {
        if (users.findByUsername("guarded").isEmpty()) {
            users.save(new AppUserEntity("guarded", "Guarded", encoder.encode(PASSWORD), "ADMIN",
                    TestTeamListener.id(teams)));
        }
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private void signIn(String address, String password, int expected) throws Exception {
        mvc.perform(post("/api/session/login").with(csrf()).with(from(address))
                .param("username", "guarded").param("password", password))
                .andExpect(status().is(expected));
    }

    private static String basic(String password) {
        return "Basic " + Base64.getEncoder().encodeToString(("guarded:" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("ten failures, then even the right password gets 429")
    void throttlesTheForm() throws Exception {
        double refusedBefore = registry.counter("duty.signin.refused").count();
        for (int i = 0; i < LoginThrottle.LIMIT; i++) {
            signIn("192.0.2.10", "wrong", 401);
        }
        mvc.perform(post("/api/session/login").with(csrf()).with(from("192.0.2.10"))
                .param("username", "guarded").param("password", PASSWORD))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.detail").value(containsString("Try again in 15 minutes")));
        assertThat(registry.counter("duty.signin.refused").count()).isEqualTo(refusedBefore + 1);

        signIn("192.0.2.11", PASSWORD, 204);
    }

    @Test
    @DisplayName("success clears the count")
    void successClears() throws Exception {
        for (int i = 0; i < LoginThrottle.LIMIT - 1; i++) {
            signIn("192.0.2.20", "wrong", 401);
        }
        signIn("192.0.2.20", PASSWORD, 204);
        for (int i = 0; i < LoginThrottle.LIMIT - 1; i++) {
            signIn("192.0.2.20", "wrong", 401);
        }
        signIn("192.0.2.20", PASSWORD, 204);
    }

    @Test
    @DisplayName("HTTP Basic failures count too")
    void throttlesBasic() throws Exception {
        for (int i = 0; i < LoginThrottle.LIMIT; i++) {
            mvc.perform(get("/api/troopers").with(from("192.0.2.30")).header("Authorization", basic("wrong")))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/troopers").with(from("192.0.2.30")).header("Authorization", basic(PASSWORD)))
                .andExpect(status().isTooManyRequests());
        // The session endpoint still works.
        mvc.perform(get("/api/session").with(from("192.0.2.30"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("CSP and no-referrer headers")
    void headers() throws Exception {
        mvc.perform(get("/api/session"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @DisplayName("health shows no details to anyone")
    void healthForAnyone() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("team admin can't read the actuator")
    void actuatorNotForAdmins() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health")).andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "OWNER")
    @DisplayName("owner can read the actuator")
    void actuatorForTheOwner() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    @DisplayName("client files are public")
    void clientIsOpen() throws Exception {
        // No client in the test build, so 404, not 401.
        for (String path : new String[] { "/", "/index.html", "/favicon.svg", "/assets/index-abc.js" }) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }

        mvc.perform(get("/api/troopers")).andExpect(status().isUnauthorized());
    }
}
