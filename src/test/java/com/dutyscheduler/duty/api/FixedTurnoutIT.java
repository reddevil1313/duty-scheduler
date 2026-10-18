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

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fixed turnouts through the API. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "admin", roles = "ADMIN")
class FixedTurnoutIT {

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

    @BeforeEach
    void seedRoster() {
        sheets.deleteAll();
        absences.deleteAll();
        troopers.deleteAll();
        for (String name : List.of("TROOPER_A", "TROOPER_B", "TROOPER_C", "TROOPER_D",
                "TROOPER_E", "TROOPER_F", "TROOPER_G")) {
            troopers.save(new TrooperEntity(name, false));
        }
        for (String name : List.of("TROOPER_H", "TROOPER_I")) {
            troopers.save(new TrooperEntity(name, true));
        }
    }

    private static String body(int turnout) {
        return """
                {"date":"2026-09-10","nightGroup":["TROOPER_A","TROOPER_B","TROOPER_C"],"turnout":%d}
                """.formatted(turnout);
    }

    @Test
    @DisplayName("5h each covers 46 of 47 hours")
    void fiveEach() throws Exception {
        mvc.perform(post("/api/schedules/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHours").value(46));
    }

    @Test
    @DisplayName("too many hours is a 422")
    void tooManyIs422() throws Exception {
        mvc.perform(post("/api/schedules/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(7)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(
                        "At 7h each the team makes 63 hours, more than the 47 this sheet needs."
                                + " Use full coverage, or fewer hours each."));
    }

    @Test
    @DisplayName("a turnout outside 5 to 7 is a 400")
    void outOfRangeIs400() throws Exception {
        mvc.perform(post("/api/schedules/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(4)))
                .andExpect(status().isBadRequest());
    }
}
