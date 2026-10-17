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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The order rows come in, and stay-outs on a weekend.
 *
 * <p>
 * The roster is seeded shuffled — stay-outs first, the night group scattered
 * — so an order that merely kept the roster's would fail here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "admin", roles = "ADMIN")
class BoardOrderIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static final String THURSDAY = "2026-09-10";
    /** Friday's sheet runs into Saturday: no hour of it is open to a stay-out. */
    static final String FRIDAY = "2026-09-11";
    /** Sunday's sheet runs into Monday, whose daytime is. */
    static final String SUNDAY = "2026-09-13";
    static final String NIGHT = "\"TROOPER_A\",\"TROOPER_B\",\"TROOPER_C\"";

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
    void shuffledRoster() {
        sheets.deleteAll();
        absences.deleteAll();
        troopers.deleteAll();
        for (String name : List.of("TROOPER_I", "TROOPER_D", "TROOPER_A", "TROOPER_E", "TROOPER_J", "TROOPER_B",
                "TROOPER_F", "TROOPER_C", "TROOPER_G", "TROOPER_H", "TROOPER_K")) {
            troopers.save(new TrooperEntity(name, List.of("TROOPER_I", "TROOPER_J", "TROOPER_K").contains(name)));
        }
    }

    private String generate(String date, String out) throws Exception {
        return mvc.perform(post("/api/schedules/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"date":"%s","nightGroup":[%s],"out":[%s]}
                        """.formatted(date, NIGHT, out)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @SuppressWarnings("unchecked")
    private List<Object> order(String sheet) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) json.readValue(sheet, Map.class).get("rows");
        return rows.stream().map(r -> r.get("trooper")).toList();
    }

    @Test
    @DisplayName("night first, then day duty, then stay-outs, then anyone away, each in roster order")
    void weekdayOrder() throws Exception {
        String sheet = generate(THURSDAY, "{\"trooper\":\"TROOPER_F\",\"kind\":\"AL\"}");
        assertThat(order(sheet)).containsExactly(
                "TROOPER_A", "TROOPER_B", "TROOPER_C",
                "TROOPER_D", "TROOPER_E", "TROOPER_G", "TROOPER_H",
                "TROOPER_I", "TROOPER_J", "TROOPER_K",
                "TROOPER_F");
    }

    @Test
    @DisplayName("on a weekend sheet the stay-outs, who cannot stand an hour of it, are listed with the away")
    void weekendStayOutsGoLast() throws Exception {
        String sheet = generate(FRIDAY, "");
        assertThat(order(sheet)).containsExactly(
                "TROOPER_A", "TROOPER_B", "TROOPER_C",
                "TROOPER_D", "TROOPER_E", "TROOPER_F", "TROOPER_G", "TROOPER_H",
                "TROOPER_I", "TROOPER_J", "TROOPER_K");
    }

    @Test
    @DisplayName("the check marks a weekend stay-out away every hour, as SO")
    void weekendStayOutsAreAway() throws Exception {
        mvc.perform(post("/api/schedules/" + FRIDAY + "/check").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(generate(FRIDAY, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.away.TROOPER_I", hasSize(24)))
                .andExpect(jsonPath("$.away.TROOPER_I", everyItem(is("SO"))))
                .andExpect(jsonPath("$.away.TROOPER_K", everyItem(is("SO"))))
                // Live-ins are not away, and nothing here is a leave clash.
                .andExpect(jsonPath("$.away.TROOPER_D").doesNotExist())
                .andExpect(jsonPath("$.issues", hasSize(0)));
    }

    @Test
    @DisplayName("a stay-out with Monday's daytime on the sheet is not away")
    void sundayStayOutsAreIn() throws Exception {
        mvc.perform(post("/api/schedules/" + SUNDAY + "/check").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(generate(SUNDAY, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.away.TROOPER_I").doesNotExist());
    }

    @Test
    @DisplayName("leave on a weekend stay-out still shows as the leave")
    void leaveWinsOverSo() throws Exception {
        String sheet = generate(FRIDAY, "{\"trooper\":\"TROOPER_J\",\"kind\":\"MC\"}");
        mvc.perform(post("/api/schedules/" + FRIDAY + "/check").param("out", "TROOPER_J:MC").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(sheet))
                .andExpect(jsonPath("$.away.TROOPER_J[0]").value("MC"))
                .andExpect(jsonPath("$.away.TROOPER_J[23]").value("MC"))
                .andExpect(jsonPath("$.away.TROOPER_I[0]").value("SO"));
        assertThat(Collections.frequency(order(sheet), "TROOPER_J")).isOne();
    }
}
