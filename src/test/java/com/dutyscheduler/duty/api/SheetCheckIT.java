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
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Checking hand-edited sheets. Each test breaks a solved sheet. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "admin", roles = "ADMIN")
class SheetCheckIT {

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> generate() throws Exception {
        String body = mvc.perform(post("/api/schedules/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"date":"%s","nightGroup":["TROOPER_A","TROOPER_B","TROOPER_C"]}
                        """.formatted(DATE)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readValue(body, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> postsOf(Map<String, Object> sheet, String trooper) {
        return ((List<Map<String, Object>>) sheet.get("rows")).stream()
                .filter(row -> row.get("trooper").equals(trooper))
                .findFirst().map(row -> (List<Object>) row.get("posts")).orElseThrow();
    }

    /** The first slot this Trooper has a post on. */
    private static int firstOnDuty(List<Object> posts) {
        for (int i = 0; i < posts.size(); i++) {
            if (posts.get(i) != null) {
                return i;
            }
        }
        throw new AssertionError("not on duty at all");
    }

    @Test
    @DisplayName("solved sheet is clean, with demand per hour")
    void solverSheetIsClean() throws Exception {
        mvc.perform(post("/api/schedules/" + DATE + "/check").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(generate())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues", hasSize(0)))
                .andExpect(jsonPath("$.demand", hasSize(24)))
                // 2000: nothing. 2200: AP and GG.
                .andExpect(jsonPath("$.demand[0]").isEmpty())
                .andExpect(jsonPath("$.demand[2].AP").value(1))
                .andExpect(jsonPath("$.demand[2].GG").value(1));
    }

    @Test
    @DisplayName("removing someone flags the hour")
    void clearingACellIsUnderManned() throws Exception {
        Map<String, Object> sheet = generate();
        List<Object> posts = postsOf(sheet, "TROOPER_A");
        int slot = firstOnDuty(posts);
        posts.set(slot, null);

        int hoursBefore = posts.stream().filter(p -> p != null).toList().size() + 1;
        mvc.perform(post("/api/schedules/" + DATE + "/check").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(sheet)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues[*].rule", hasItem("UNDER_MANNED")))
                .andExpect(jsonPath("$.issues[*].slot", hasItem(slot)))

                .andExpect(jsonPath("$.sheet.rows[?(@.trooper == 'TROOPER_A')].hours", hasItem(hoursBefore - 1)))
                .andExpect(jsonPath("$.sheet.totalHours").value(46));
    }

    @Test
    @DisplayName("duty during leave is flagged")
    void dutyDuringLeaveIsFlagged() throws Exception {
        Map<String, Object> sheet = generate();
        int slot = firstOnDuty(postsOf(sheet, "TROOPER_D"));
        // Booked after generating.
        mvc.perform(post("/api/absences").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"trooper":"TROOPER_D","kind":"MC","span":"FULL","from":"2026-09-10","to":"2026-09-11"}
                        """))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/schedules/" + DATE + "/check").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(sheet)))
                .andExpect(jsonPath("$.issues[?(@.rule == 'ON_LEAVE')].trooper", hasItem("TROOPER_D")))
                .andExpect(jsonPath("$.issues[?(@.rule == 'ON_LEAVE')].slot", hasItem(slot)));
    }

    @Test
    @DisplayName("away hours: all for an MC, three for a 0930-1130 appointment")
    @SuppressWarnings("unchecked")
    void awayHours() throws Exception {
        mvc.perform(post("/api/absences").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"trooper":"TROOPER_D","kind":"MC","span":"FULL","from":"2026-09-10","to":"2026-09-11"}
                        """))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/absences").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"trooper":"TROOPER_E","kind":"MA","span":"WINDOW","from":"2026-09-11","start":"09:30","end":"11:30"}
                        """))
                .andExpect(status().isCreated());

        String body = mvc.perform(post("/api/schedules/" + DATE + "/check").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(generate())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, List<String>> away = (Map<String, List<String>>) json.readValue(body, Map.class).get("away");

        assertThat(away.get("TROOPER_D")).hasSize(24).containsOnly("MC");
        List<String> e = away.get("TROOPER_E");
        assertThat(e).hasSize(24);
        for (int slot = 0; slot < 24; slot++) {
            // Slots 13-15 are 0900-1100.
            assertThat(e.get(slot)).isEqualTo(slot >= 13 && slot <= 15 ? "MA" : null);
        }
        assertThat(away).doesNotContainKey("TROOPER_A");
    }

    @Test
    @DisplayName("a sheet that breaks a rule can still be logged")
    void ruleBreakingSheetStillLogs() throws Exception {
        Map<String, Object> sheet = generate();
        postsOf(sheet, "TROOPER_A").set(firstOnDuty(postsOf(sheet, "TROOPER_A")), null);

        mvc.perform(put("/api/schedules/" + DATE).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(sheet)))
                .andExpect(status().isOk());
        assertThat(sheets.existsByDutyDate(java.time.LocalDate.parse(DATE))).isTrue();
    }

    @Test
    @WithMockUser(username = "trooper_d", roles = "USER")
    @DisplayName("any user may check")
    void usersMayCheck() throws Exception {
        mvc.perform(post("/api/schedules/" + DATE + "/check").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"date":"%s","rows":[],"totalHours":0}
                        """.formatted(DATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues[*].rule", hasItem("UNDER_MANNED")));
    }
}
