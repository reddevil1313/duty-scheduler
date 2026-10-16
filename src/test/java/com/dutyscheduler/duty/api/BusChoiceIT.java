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

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The BUS choice end to end: sent with a generate, refused with a reason when it cannot stand. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "admin", roles = "ADMIN")
class BusChoiceIT {

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

    private static String body(String bus, String out) {
        return """
                {"date":"2026-09-10","nightGroup":["TROOPER_A","TROOPER_B","TROOPER_C"],"out":[%s],"bus":"%s"}
                """.formatted(out, bus);
    }

    @Test
    @DisplayName("the chosen Trooper has every BUS hour on the sheet that comes back")
    void chosenGetsBus() throws Exception {
        mvc.perform(post("/api/schedules/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("TROOPER_F", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.posts[11] == 'BUS')].trooper", hasSize(1)))
                .andExpect(jsonPath("$.rows[?(@.posts[11] == 'BUS' || @.posts[12] == 'BUS' || @.posts[13] == 'BUS')].trooper",
                        everyItem(is("TROOPER_F"))));
    }

    @Test
    @DisplayName("a stay-out as BUS is a 422 that says why")
    void stayOutIs422() throws Exception {
        mvc.perform(post("/api/schedules/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("TROOPER_H", "")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(
                        "TROOPER_H cannot take BUS: they are not available at 0700, being a stay-out."));
    }

    @Test
    @DisplayName("someone ticked out on the roster cannot be the BUS Trooper either")
    void tickedOutIs422() throws Exception {
        mvc.perform(post("/api/schedules/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("TROOPER_D", "{\"trooper\":\"TROOPER_D\",\"kind\":\"MC\"}")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(
                        "TROOPER_D cannot take BUS: they are not available at 0700, being on leave."));
    }

    @Test
    @DisplayName("a BUS name not on the roster is a 400")
    void unknownIs400() throws Exception {
        mvc.perform(post("/api/schedules/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("NOBODY", "")))
                .andExpect(status().isBadRequest());
    }
}
