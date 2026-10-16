package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.persistence.AbsenceRepository;
import com.dutyscheduler.duty.persistence.DutySheetRepository;
import com.dutyscheduler.duty.persistence.HourCreditRepository;
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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The records add up from four sources — logged grids, credits, opening hours
 * and the leave register — so each test plants a known amount in each and
 * checks the sums rather than trusting any one of them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "admin", roles = "ADMIN")
class RecordsIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

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

    @Autowired
    HourCreditRepository credits;

    @BeforeEach
    void seedRoster() {
        credits.deleteAll();
        sheets.deleteAll();
        absences.deleteAll();
        troopers.deleteAll();
        List<String> night = List.of("TROOPER_A", "TROOPER_B", "TROOPER_C");
        for (String name : List.of("TROOPER_A", "TROOPER_B", "TROOPER_C", "TROOPER_D",
                "TROOPER_E", "TROOPER_F", "TROOPER_G", "TROOPER_H")) {
            TrooperEntity entity = new TrooperEntity(name, false);
            entity.setOnNight(night.contains(name));
            troopers.save(entity);
        }
        for (String name : List.of("TROOPER_I", "TROOPER_J", "TROOPER_K")) {
            troopers.save(new TrooperEntity(name, true));
        }
    }

    /** Generate a sheet and log it unchanged. */
    private void logSheet(String date) throws Exception {
        String sheet = mvc.perform(post("/api/schedules/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"date":"%s","nightGroup":["TROOPER_A","TROOPER_B","TROOPER_C"]}
                        """.formatted(date)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(put("/api/schedules/" + date).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(sheet))
                .andExpect(status().isOk());
    }

    private void book(String body) throws Exception {
        mvc.perform(post("/api/absences").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> recordOf(String name) throws Exception {
        String body = mvc.perform(get("/api/records")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> rows = (List<Map<String, Object>>) json.readValue(body, Map.class).get("troopers");
        return rows.stream().filter(r -> r.get("name").equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("worked hours across everyone add up to the hours the logged sheets demanded")
    void workedAddsUp() throws Exception {
        logSheet("2026-09-10");
        logSheet("2026-09-11");

        // Thursday's sheet is 47 post-hours and Friday's 44.
        mvc.perform(get("/api/records"))
                .andExpect(jsonPath("$.days[*].date", contains("2026-09-11", "2026-09-10")))
                .andExpect(jsonPath("$.days[*].worked", contains(44, 47)))
                .andExpect(jsonPath("$.days[*].issues", contains(0, 0)))
                .andExpect(jsonPath("$.days[1].night", containsInAnyOrder("TROOPER_A", "TROOPER_B", "TROOPER_C")))
                .andExpect(jsonPath("$.days[1].bus.length()", greaterThan(0)));

        String body = mvc.perform(get("/api/records")).andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) json.readValue(body, Map.class).get("troopers");
        assertThat(rows.stream().mapToInt(r -> (Integer) r.get("worked")).sum()).isEqualTo(91);
        assertThat(rows.stream().mapToInt(r -> (Integer) r.get("nights")).sum()).isEqualTo(6);
    }

    @Test
    @DisplayName("the total is opening plus worked plus credited")
    void totalIsTheSumOfItsParts() throws Exception {
        logSheet("2026-09-10");
        mvc.perform(put("/api/records/opening").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"TROOPER_D":100}
                        """))
                .andExpect(status().isOk());
        mvc.perform(put("/api/schedules/2026-09-10/credits").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"TROOPER_D":6,"TROOPER_E":0}
                        """))
                .andExpect(status().isNoContent());

        Map<String, Object> d = recordOf("TROOPER_D");
        int worked = (Integer) d.get("worked");
        assertThat(worked).isPositive();
        assertThat(d.get("opening")).isEqualTo(100);
        assertThat(d.get("credited")).isEqualTo(6);
        assertThat(d.get("total")).isEqualTo(100 + worked + 6);
        // Zero credits nobody.
        assertThat(recordOf("TROOPER_E").get("credited")).isEqualTo(0);

        mvc.perform(get("/api/records"))
                .andExpect(jsonPath("$.days[0].credited").value(6))
                .andExpect(jsonPath("$.days[0].credits.TROOPER_D").value(6));
    }

    @Test
    @DisplayName("crediting a day again replaces what was credited, rather than adding to it")
    void creditsReplace() throws Exception {
        logSheet("2026-09-10");
        for (int hours : new int[] { 6, 4 }) {
            mvc.perform(put("/api/schedules/2026-09-10/credits").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"TROOPER_D\":" + hours + "}"))
                    .andExpect(status().isNoContent());
        }
        assertThat(recordOf("TROOPER_D").get("credited")).isEqualTo(4);
    }

    @Test
    @DisplayName("leave counts in days, a half day as half, an appointment as one")
    void leaveCounts() throws Exception {
        book("""
                {"trooper":"TROOPER_D","kind":"MC","span":"FULL","from":"2026-09-10","to":"2026-09-12"}
                """);
        book("""
                {"trooper":"TROOPER_D","kind":"OL","span":"DAY_HALF","from":"2026-09-20"}
                """);
        book("""
                {"trooper":"TROOPER_D","kind":"MA","span":"WINDOW","from":"2026-09-21","start":"09:00","end":"10:00"}
                """);

        Map<String, Object> d = recordOf("TROOPER_D");
        assertThat(d.get("mc")).isEqualTo(3.0);
        assertThat(d.get("ol")).isEqualTo(0.5);
        assertThat(d.get("al")).isEqualTo(0.0);
        assertThat(d.get("ma")).isEqualTo(1);
    }

    @Test
    @DisplayName("a day logged with a broken rule says how many it breaks")
    @SuppressWarnings("unchecked")
    void brokenDayIsCounted() throws Exception {
        String body = mvc.perform(post("/api/schedules/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"date":"2026-09-10","nightGroup":["TROOPER_A","TROOPER_B","TROOPER_C"]}
                        """))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> sheet = json.readValue(body, Map.class);
        List<Object> posts = (List<Object>) ((List<Map<String, Object>>) sheet.get("rows")).get(0).get("posts");
        for (int i = 0; i < posts.size(); i++) {
            if (posts.get(i) != null) {
                posts.set(i, null);
                break;
            }
        }
        mvc.perform(put("/api/schedules/2026-09-10").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(sheet)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/records")).andExpect(jsonPath("$.days[0].issues", greaterThan(0)));
    }

    @Test
    @DisplayName("credits need a logged day (404), and bad numbers are refused (400)")
    void refusals() throws Exception {
        mvc.perform(put("/api/schedules/2026-09-10/credits").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"TROOPER_D\":6}"))
                .andExpect(status().isNotFound());

        logSheet("2026-09-10");
        mvc.perform(put("/api/schedules/2026-09-10/credits").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"TROOPER_D\":30}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/schedules/2026-09-10/credits").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"NOBODY\":3}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/records/opening").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"TROOPER_D\":-1}"))
                .andExpect(status().isBadRequest());

        // All or nothing: the good entry beside a bad one is not saved either.
        mvc.perform(put("/api/records/opening").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"TROOPER_D\":10,\"NOBODY\":3}"))
                .andExpect(status().isBadRequest());
        assertThat(troopers.findByName("TROOPER_D").orElseThrow().getOpeningHours()).isZero();
    }

    @Test
    @WithMockUser(username = "trooper_d", roles = "USER")
    @DisplayName("a Trooper can read the records and change none of them")
    void usersReadOnly() throws Exception {
        mvc.perform(get("/api/records")).andExpect(status().isOk());
        mvc.perform(put("/api/records/opening").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"TROOPER_D\":10}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/schedules/2026-09-10/credits").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"TROOPER_D\":6}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("someone whose only history is a credit is archived on removal, and kept in the records")
    void creditOnlyHistoryArchives() throws Exception {
        TrooperEntity extra = troopers.save(new TrooperEntity("TROOPER_L", false));
        logSheet("2026-09-10");
        mvc.perform(put("/api/schedules/2026-09-10/credits").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"TROOPER_L\":5}"))
                .andExpect(status().isNoContent());

        mvc.perform(delete("/api/troopers/" + extra.getId()).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(troopers.findById(extra.getId())).map(TrooperEntity::isActive).hasValue(false);
        mvc.perform(get("/api/records"))
                .andExpect(jsonPath("$.troopers[*].name", hasItem("TROOPER_L")))
                .andExpect(jsonPath("$.troopers[?(@.name == 'TROOPER_L')].active", contains(false)));
    }
}
