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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reuse through the API, and the turnout stored when logging. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "admin", roles = "ADMIN")
class SheetReuseIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final String THURSDAY = "2026-09-10";
    private static final String MONDAY = "2026-09-14";
    private static final String FRIDAY = "2026-09-11";

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
        for (String name : List.of("TROOPER_A", "TROOPER_B", "TROOPER_C", "TROOPER_D",
                "TROOPER_E", "TROOPER_F", "TROOPER_G")) {
            troopers.save(new TrooperEntity(name, false));
        }
        for (String name : List.of("TROOPER_H", "TROOPER_I")) {
            troopers.save(new TrooperEntity(name, true));
        }
    }

    private String generate(String date, String extra) throws Exception {
        return mvc.perform(post("/api/schedules/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"date":"%s","nightGroup":["TROOPER_A","TROOPER_B","TROOPER_C"]%s}
                        """.formatted(date, extra)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void log(String date, String sheet) throws Exception {
        mvc.perform(put("/api/schedules/" + date).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(sheet))
                .andExpect(status().isOk());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String body) {
        return json.readValue(body, Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<String> turnouts(String sheet) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) parse(sheet).get("rows")) {
            if ((Integer) row.get("hours") > 0) {
                out.add(row.get("posts").toString());
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    @Test
    @DisplayName("Monday reuses Thursday and says so")
    void mondayReusesThursday() throws Exception {
        String thursday = generate(THURSDAY, "");
        log(THURSDAY, thursday);

        String monday = generate(MONDAY, "");

        assertThat(parse(monday).get("reusedFrom")).isEqualTo(THURSDAY);
        assertThat(turnouts(monday)).isEqualTo(turnouts(thursday));
    }

    @Test
    @DisplayName("Friday has different posts, so is solved")
    void fridayIsSolved() throws Exception {
        log(THURSDAY, generate(THURSDAY, ""));
        assertThat(parse(generate(FRIDAY, "")).get("reusedFrom")).isNull();
    }

    @Test
    @DisplayName("one ticked out is a different headcount")
    void headcountDiffers() throws Exception {
        log(THURSDAY, generate(THURSDAY, ""));
        String monday = generate(MONDAY, ",\"out\":[{\"trooper\":\"TROOPER_G\",\"kind\":\"OL\"}]");
        assertThat(parse(monday).get("reusedFrom")).isNull();
    }

    @Test
    @DisplayName("different turnout doesn't match")
    void turnoutDiffers() throws Exception {
        log(THURSDAY, generate(THURSDAY, ""));
        assertThat(parse(generate(MONDAY, ",\"turnout\":5")).get("reusedFrom")).isNull();
    }

    @Test
    @DisplayName("regenerating doesn't reuse the same day")
    void notItsOwnSheet() throws Exception {
        log(THURSDAY, generate(THURSDAY, ""));
        assertThat(parse(generate(THURSDAY, "")).get("reusedFrom")).isNull();
    }

    @Test
    @DisplayName("5h Thursday serves a 5h Monday")
    void fixedTurnoutRoundTrip() throws Exception {
        String thursday = generate(THURSDAY, ",\"turnout\":5");
        assertThat(parse(thursday).get("turnout")).isEqualTo(5);
        log(THURSDAY, thursday);

        mvc.perform(get("/api/schedules/" + THURSDAY)).andExpect(jsonPath("$.turnout").value(5));
        mvc.perform(get("/api/records")).andExpect(jsonPath("$.days[0].issues").value(0));
        assertThat(parse(generate(MONDAY, ",\"turnout\":5")).get("reusedFrom")).isEqualTo(THURSDAY);
    }

    @Test
    @DisplayName("free split logs no turnout")
    void freeSplitHasNoTurnout() throws Exception {
        log(THURSDAY, generate(THURSDAY, ""));
        mvc.perform(get("/api/schedules/" + THURSDAY)).andExpect(jsonPath("$.turnout").value(nullValue()));
    }

    @Test
    @DisplayName("a turnout outside 5 to 7 cannot be logged")
    void badTurnoutIs400() throws Exception {
        String thursday = generate(THURSDAY, "").replaceFirst("\"turnout\":null", "\"turnout\":9");
        mvc.perform(put("/api/schedules/" + THURSDAY).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(thursday))
                .andExpect(status().isBadRequest());
    }
}
