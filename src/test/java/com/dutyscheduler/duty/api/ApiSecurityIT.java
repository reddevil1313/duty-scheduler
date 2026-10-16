package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.persistence.AbsenceRepository;
import com.dutyscheduler.duty.persistence.DutySheetRepository;
import com.dutyscheduler.duty.persistence.TrooperEntity;
import com.dutyscheduler.duty.persistence.TrooperRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the roles actually do, tested through the HTTP layer.
 *
 * <p>
 * The UI will hide buttons a Trooper cannot use, and that is a courtesy, not a
 * control — anyone can open a terminal. These tests go straight at the
 * endpoints with a Trooper's credentials and insist on a 403, because that is
 * the only place the answer really lives.
 *
 * <p>
 * Note the {@code csrf()} post-processor on every write. Without it the writes
 * would fail with 403 too, and the test would pass for entirely the wrong
 * reason:
 * it would be proving CSRF is on rather than that roles are enforced. Passing a
 * valid token is what makes the Trooper's 403 mean what it claims to.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ApiSecurityIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    TrooperRepository troopers;

    @Autowired
    DutySheetRepository sheets;

    @Autowired
    AbsenceRepository absences;

    private static final List<String> ROSTER = List.of(
            "TROOPER_A", "TROOPER_B", "TROOPER_C", "TROOPER_D", "TROOPER_E",
            "TROOPER_F", "TROOPER_G", "TROOPER_H");
    private static final List<String> STAY_OUTS = List.of("TROOPER_I", "TROOPER_J", "TROOPER_K");
    private static final List<String> NIGHT = List.of("TROOPER_A", "TROOPER_B", "TROOPER_C");

    private static final String GENERATE_BODY = """
            {"date":"2026-09-10","nightGroup":["TROOPER_A","TROOPER_B","TROOPER_C"]}
            """;
    private static final String ABSENCE_BODY = """
            {"trooper":"TROOPER_D","kind":"MC","span":"FULL","from":"2026-09-10","to":"2026-09-12"}
            """;

    @BeforeEach
    void seedRoster() {
        sheets.deleteAll();
        absences.deleteAll();
        troopers.deleteAll();
        for (String name : ROSTER) {
            TrooperEntity entity = new TrooperEntity(name, false);
            entity.setOnNight(NIGHT.contains(name));
            troopers.save(entity);
        }
        for (String name : STAY_OUTS) {
            troopers.save(new TrooperEntity(name, true));
        }
    }

    @Test
    @DisplayName("nobody gets in without an account")
    void anonymousIsRefused() throws Exception {
        mvc.perform(get("/api/schedules/2026-09-10")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/absences")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/schedules/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(GENERATE_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the session endpoint answers an anonymous caller, and hands out a token")
    void anonymousStillGetsACsrfToken() throws Exception {
        // The one endpoint that must work signed out. The login POST is itself
        // CSRF-protected, so a client with no token cannot sign in — and a 401
        // here would leave it with no way to get one.
        mvc.perform(get("/api/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.username").value(nullValue()))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty());
    }

    @Test
    @DisplayName("the session endpoint names the signed-in user and their role")
    void sessionNamesTheUser() throws Exception {
        mvc.perform(get("/api/session").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("admin"))
                // ADMIN, not ROLE_ADMIN. The prefix is Spring's business, not the client's.
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    @DisplayName("a POST to the session endpoint is not permitted by the anonymous rule")
    void onlyGetIsOpen() throws Exception {
        // The permitAll rule names GET explicitly. If it were written as a bare
        // path it would open every method on it, and this test is what would
        // notice.
        mvc.perform(post("/api/session").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "trooper_d", roles = "USER")
    @DisplayName("a Trooper can read the roster")
    void trooperCanReadRoster() throws Exception {
        mvc.perform(get("/api/troopers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].name").value("TROOPER_A"));
    }

    @Test
    @DisplayName("the roster is not public")
    void rosterNeedsAnAccount() throws Exception {
        mvc.perform(get("/api/troopers")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    @DisplayName("an ADMIN adds a Trooper, and they join the end of the roster")
    void adminAddsTrooper() throws Exception {
        mvc.perform(post("/api/troopers").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"  TROOPER_L ","rank":"","stayOut":false,"onNight":true}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("TROOPER_L"))
                .andExpect(jsonPath("$.rank").value(nullValue()))
                .andExpect(jsonPath("$.onNight").value(true));

        mvc.perform(get("/api/troopers"))
                .andExpect(jsonPath("$.length()").value(ROSTER.size() + STAY_OUTS.size() + 1))
                .andExpect(jsonPath("$[-1].name").value("TROOPER_L"));
    }

    @Test
    @WithMockUser(username = "trooper_d", roles = "USER")
    @DisplayName("a Trooper cannot add to the roster")
    void trooperCannotAddTrooper() throws Exception {
        mvc.perform(post("/api/troopers").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"TROOPER_L","stayOut":false,"onNight":false}
                        """))
                .andExpect(status().isForbidden());
        assertThat(troopers.findByName("TROOPER_L")).isEmpty();
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    @DisplayName("a name already on the roster is a 409, not a second row")
    void duplicateNameConflicts() throws Exception {
        mvc.perform(post("/api/troopers").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"TROOPER_A","stayOut":false,"onNight":false}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("TROOPER_A is already on the roster."));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    @DisplayName("a stay-out on the night group is a 422: well formed, but impossible")
    void stayOutOnNightIsUnprocessable() throws Exception {
        mvc.perform(post("/api/troopers").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"TROOPER_L","stayOut":true,"onNight":true}
                        """))
                .andExpect(status().isUnprocessableEntity());
        assertThat(troopers.findByName("TROOPER_L")).isEmpty();
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    @DisplayName("a blank name is a 400")
    void blankNameIsBadRequest() throws Exception {
        mvc.perform(post("/api/troopers").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"   ","stayOut":false,"onNight":false}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a wrong password is a 401 with a problem body, not a redirect")
    void badSignInIsUnauthorized() throws Exception {
        // Left at its default, formLogin answers with a 302 to /login?error.
        // fetch() follows redirects silently, so the browser would see a 200 and
        // report a successful sign-in. This is the test that pins the status.
        mvc.perform(post("/api/session/login").with(csrf())
                .param("username", "admin")
                .param("password", "not-the-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Sign-in failed"));
    }

    @Test
    @DisplayName("health is open, because a load balancer has no account")
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "trooper_d", roles = "USER")
    @DisplayName("a trooper can read the board and the register")
    void stCanRead() throws Exception {
        mvc.perform(get("/api/absences")).andExpect(status().isOk());
        // Nothing logged for that date yet, so 404 — but a 404 means he got past
        // security, which is the thing under test.
        mvc.perform(get("/api/schedules/2026-09-10")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "trooper_d", roles = "USER")
    @DisplayName("a trooper cannot generate, log or book — every write is refused")
    void stCannotWrite() throws Exception {
        mvc.perform(post("/api/schedules/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(GENERATE_BODY))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/absences").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(ABSENCE_BODY))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/absences/1").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    @DisplayName("an ADMIN can generate a sheet")
    void icCanGenerate() throws Exception {
        mvc.perform(post("/api/schedules/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(GENERATE_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    @DisplayName("an ADMIN can book leave")
    void icCanBookLeave() throws Exception {
        mvc.perform(post("/api/absences").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(ABSENCE_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    @DisplayName("a write without a CSRF token is refused even for an ADMIN")
    void csrfIsEnforced() throws Exception {
        mvc.perform(post("/api/absences")
                .contentType(MediaType.APPLICATION_JSON).content(ABSENCE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    @DisplayName("a well-formed request the manpower cannot cover is 422, not 400")
    void unsolvableIsUnprocessable() throws Exception {
        // Two men on night cannot hold sixteen silent hours, whatever else is true.
        String impossible = """
                {"date":"2026-09-10","nightGroup":["TROOPER_A","TROOPER_B"]}
                """;
        mvc.perform(post("/api/schedules/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(impossible))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    @DisplayName("a malformed request is 400")
    void malformedIsBadRequest() throws Exception {
        mvc.perform(post("/api/absences").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"trooper":"NOBODY","kind":"MC","span":"FULL","from":"2026-09-10","to":"2026-09-12"}
                        """))
                .andExpect(status().isBadRequest());
    }
}
