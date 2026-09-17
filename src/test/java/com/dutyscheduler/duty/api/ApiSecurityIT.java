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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
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
 * valid token is what makes the ST's 403 mean what it claims to.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ApiSecurityIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

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
