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
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Roster ticks affect one request and save nothing. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "admin", roles = "ADMIN")
class QuickLeaveIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final String DATE = "2026-09-10";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

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
                "TROOPER_E", "TROOPER_F", "TROOPER_G", "TROOPER_H")) {
            troopers.save(new TrooperEntity(name, false));
        }
        for (String name : List.of("TROOPER_I", "TROOPER_J", "TROOPER_K")) {
            troopers.save(new TrooperEntity(name, true));
        }
    }

    private String generate(String night, String out) throws Exception {
        return mvc.perform(post("/api/schedules/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"date":"%s","nightGroup":[%s],"out":[%s]}
                        """.formatted(DATE, night, out)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @SuppressWarnings("unchecked")
    private int hoursOf(String sheet, String trooper) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) json.readValue(sheet, Map.class).get("rows");
        return rows.stream().filter(r -> r.get("trooper").equals(trooper))
                .mapToInt(r -> (Integer) r.get("hours")).findFirst().orElse(0);
    }

    @Test
    @DisplayName("ticked Trooper does no hours and nothing is booked")
    void tickedOutDoesNothing() throws Exception {
        String sheet = generate("\"TROOPER_A\",\"TROOPER_B\",\"TROOPER_C\"",
                "{\"trooper\":\"TROOPER_D\",\"kind\":\"MC\"}");

        assertThat(hoursOf(sheet, "TROOPER_D")).isZero();
        assertThat((Integer) json.readValue(sheet, Map.class).get("totalHours")).isEqualTo(47);
        assertThat(absences.count()).as("a tick is not a booking").isZero();
    }

    @Test
    @DisplayName("someone out all sheet is listed last")
    @SuppressWarnings("unchecked")
    void unavailableRowsGoLast() throws Exception {
        String sheet = generate("\"TROOPER_A\",\"TROOPER_B\",\"TROOPER_C\"",
                "{\"trooper\":\"TROOPER_D\",\"kind\":\"MC\"},{\"trooper\":\"TROOPER_F\",\"kind\":\"AL\"}");

        List<Map<String, Object>> rows = (List<Map<String, Object>>) json.readValue(sheet, Map.class).get("rows");
        assertThat(rows).extracting(r -> r.get("trooper")).containsExactly(
                "TROOPER_A", "TROOPER_B", "TROOPER_C", "TROOPER_E", "TROOPER_G", "TROOPER_H",
                "TROOPER_I", "TROOPER_J", "TROOPER_K", "TROOPER_D", "TROOPER_F");
    }

    @Test
    @DisplayName("a tick lasts one request")
    void tickIsForgotten() throws Exception {
        generate("\"TROOPER_A\",\"TROOPER_B\",\"TROOPER_C\"", "{\"trooper\":\"TROOPER_D\",\"kind\":\"OL\"}");

        String again = generate("\"TROOPER_A\",\"TROOPER_B\",\"TROOPER_C\"", "");
        assertThat(hoursOf(again, "TROOPER_D")).isPositive();
    }

    @Test
    @DisplayName("ticked out is dropped from the night group")
    void tickedOutLeavesTheNightGroup() throws Exception {
        String sheet = generate("\"TROOPER_A\",\"TROOPER_B\",\"TROOPER_C\",\"TROOPER_D\"",
                "{\"trooper\":\"TROOPER_A\",\"kind\":\"AL\"}");

        assertThat(hoursOf(sheet, "TROOPER_A")).isZero();
        assertThat(hoursOf(sheet, "TROOPER_D")).isPositive();
    }

    @Test
    @DisplayName("MA or an unknown name is a 400")
    void badTicks() throws Exception {
        for (String out : new String[] {
                "{\"trooper\":\"TROOPER_D\",\"kind\":\"MA\"}",
                "{\"trooper\":\"NOBODY\",\"kind\":\"MC\"}" }) {
            mvc.perform(post("/api/schedules/generate").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"date":"%s","nightGroup":["TROOPER_A","TROOPER_B","TROOPER_C"],"out":[%s]}
                            """.formatted(DATE, out)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("check marks the ticked Trooper away and flags duty")
    void checkSeesTheTick() throws Exception {
        String sheet = generate("\"TROOPER_A\",\"TROOPER_B\",\"TROOPER_C\"", "");
        assertThat(hoursOf(sheet, "TROOPER_D")).as("on duty without the tick").isPositive();

        mvc.perform(post("/api/schedules/" + DATE + "/check").param("out", "TROOPER_D:MC").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(sheet))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.away.TROOPER_D[0]").value("MC"))
                .andExpect(jsonPath("$.away.TROOPER_D[23]").value("MC"))
                .andExpect(jsonPath("$.issues[?(@.rule == 'ON_LEAVE')].trooper", hasItem("TROOPER_D")));
        assertThat(absences.count()).isZero();
    }

    @Test
    @DisplayName("malformed tick is a 400")
    void malformedCheckTick() throws Exception {
        String sheet = generate("\"TROOPER_A\",\"TROOPER_B\",\"TROOPER_C\"", "");
        mvc.perform(post("/api/schedules/" + DATE + "/check").param("out", "TROOPER_D").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(sheet))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A tick is NAME:KIND, such as TROOPER_D:MC; got TROOPER_D"));
    }
}
