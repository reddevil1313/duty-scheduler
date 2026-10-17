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
 * The defences that sit around the endpoints rather than on them: the
 * sign-in throttle, the response headers, and who may read the actuator.
 *
 * <p>
 * Each throttle test uses its own client address, because the counts live in
 * one throttle for the whole application context.
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
    @DisplayName("ten wrong passwords from one address, and the right one is refused there with a 429")
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
        // Another address is not held to it.
        signIn("192.0.2.11", PASSWORD, 204);
    }

    @Test
    @DisplayName("a right password clears the count before it reaches the limit")
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
    @DisplayName("HTTP Basic guesses count too, and are refused the same way")
    void throttlesBasic() throws Exception {
        for (int i = 0; i < LoginThrottle.LIMIT; i++) {
            mvc.perform(get("/api/troopers").with(from("192.0.2.30")).header("Authorization", basic("wrong")))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/troopers").with(from("192.0.2.30")).header("Authorization", basic(PASSWORD)))
                .andExpect(status().isTooManyRequests());
        // Reading the session, which tries no password, still works from there.
        mvc.perform(get("/api/session").with(from("192.0.2.30"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("every response carries the content security policy and sends no Referer")
    void headers() throws Exception {
        mvc.perform(get("/api/session"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @DisplayName("health says up or down to anyone, and names nothing behind it")
    void healthForAnyone() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("a team admin may not read the rest of the actuator")
    void actuatorNotForAdmins() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health")).andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "OWNER")
    @DisplayName("the owner reads the actuator, health details included")
    void actuatorForTheOwner() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    @DisplayName("the client's own files are open to anyone, since signing in happens inside them")
    void clientIsOpen() throws Exception {
        // No client is built into the test classpath, so these are 404s: what
        // matters is that they are not 401s.
        for (String path : new String[] { "/", "/index.html", "/favicon.svg", "/assets/index-abc.js" }) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
        // And nothing else is, by being near them.
        mvc.perform(get("/api/troopers")).andExpect(status().isUnauthorized());
    }
}
