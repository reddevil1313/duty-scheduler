package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.persistence.AbsenceRepository;
import com.dutyscheduler.duty.persistence.DutySheetRepository;
import com.dutyscheduler.duty.persistence.TrooperEntity;
import com.dutyscheduler.duty.persistence.TrooperRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The leave register as the client uses it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "admin", roles = "ADMIN")
class LeaveRegisterIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    TrooperRepository troopers;

    @Autowired
    AbsenceRepository absences;

    @Autowired
    DutySheetRepository sheets;

    @BeforeEach
    void seed() {
        sheets.deleteAll();
        absences.deleteAll();
        troopers.deleteAll();
        troopers.save(new TrooperEntity("TROOPER_A", false));
        troopers.save(new TrooperEntity("TROOPER_B", false));
    }

    @Test
    @DisplayName("the register lists the latest leave first")
    void latestFirst() throws Exception {
        for (String from : new String[] { "2026-09-10", "2026-09-20", "2026-09-01" }) {
            mvc.perform(post("/api/absences").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"trooper":"TROOPER_A","kind":"AL","span":"FULL","from":"%s"}
                            """.formatted(from)))
                    .andExpect(status().isCreated());
        }

        mvc.perform(get("/api/absences"))
                .andExpect(jsonPath("$[*].from", contains("2026-09-20", "2026-09-10", "2026-09-01")));
        // The filtered read had the same lazy-Trooper failure, so it gets a look too.
        mvc.perform(get("/api/absences").param("trooper", "TROOPER_A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].trooper", contains("TROOPER_A", "TROOPER_A", "TROOPER_A")));
    }
}
