package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.persistence.AbsenceRepository;
import com.dutyscheduler.duty.persistence.DutySheetRepository;
import com.dutyscheduler.duty.persistence.TrooperEntity;
import com.dutyscheduler.duty.persistence.TrooperRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The leave register: reach, notes, order and preview. */
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

    /** Mostly the day before the date booked. */
    @ParameterizedTest(name = "{0}: {5}")
    @CsvSource(delimiter = '|', textBlock = """
            MC | FULL       | 2026-09-10 | 2026-09-12 |             | 2026-09-09 2026-09-10 2026-09-11 | an MC until the 12th never touches the 12th's sheet
            AL | FULL       | 2026-09-10 | 2026-09-10 |             | 2026-09-09                       | a single day off is exactly one sheet, the one running through it
            OL | DAY_HALF   | 2026-09-10 |            |             | 2026-09-09                       | the day half is the morning after the 9th's evening
            OL | NIGHT_HALF | 2026-09-10 |            |             | 2026-09-10                       | the night half is the 10th's own evening
            MA | WINDOW     | 2026-09-11 |            | 09:30-11:30 | 2026-09-10                       | a morning appointment lands on the previous day's sheet
            MA | WINDOW     | 2026-09-11 |            | 20:30-21:30 | 2026-09-11                       | an evening appointment lands on the same day's sheet
            """)
    void sheetsReached(String kind, String span, String from, String to, String window,
            String expected, String why) throws Exception {
        String times = window == null ? "" : """
                ,"start":"%s","end":"%s\"""".formatted(window.split("-")[0], window.split("-")[1]);
        String body = """
                {"trooper":"TROOPER_A","kind":"%s","span":"%s","from":"%s"%s%s}
                """.formatted(kind, span, from, to == null ? "" : ",\"to\":\"" + to + "\"", times);

        mvc.perform(post("/api/absences").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sheets", contains(Arrays.stream(expected.split(" ")).toArray())));
    }

    @Test
    @DisplayName("notes are trimmed; blank means none")
    void noteIsKept() throws Exception {
        mvc.perform(post("/api/absences").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"trooper":"TROOPER_A","kind":"MC","span":"FULL","from":"2026-09-10","note":"  clinic ref 12 "}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.note").value("clinic ref 12"));
        mvc.perform(post("/api/absences").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"trooper":"TROOPER_B","kind":"AL","span":"FULL","from":"2026-09-10","note":"   "}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.note").value(nullValue()));
    }

    @Test
    @DisplayName("latest leave first")
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
        // The filtered read too.
        mvc.perform(get("/api/absences").param("trooper", "TROOPER_A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].trooper", contains("TROOPER_A", "TROOPER_A", "TROOPER_A")));
    }

    @Test
    @DisplayName("preview books nothing")
    void previewSavesNothing() throws Exception {
        mvc.perform(post("/api/absences/preview").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"trooper":"TROOPER_A","kind":"MC","span":"FULL","from":"2026-09-10","to":"2026-09-12"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.covers").value("2026-09-10T00:00:00"))
                .andExpect(jsonPath("$.until").value("2026-09-12T20:00:00"))
                .andExpect(jsonPath("$.sheets", contains("2026-09-09", "2026-09-10", "2026-09-11")));

        assertThat(absences.count()).isZero();
    }

    @Test
    @DisplayName("preview of a bad booking says why")
    void previewExplainsAMistake() throws Exception {
        mvc.perform(post("/api/absences/preview").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"trooper":"TROOPER_A","kind":"MA","span":"WINDOW","from":"2026-09-10","start":"11:00","end":"09:00"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A window ends before it starts: 11:00 to 09:00"));
    }

    @Test
    @WithMockUser(username = "trooper_d", roles = "USER")
    @DisplayName("any user may preview")
    void previewIsOpenToUsers() throws Exception {
        mvc.perform(post("/api/absences/preview").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"trooper":"TROOPER_A","kind":"AL","span":"FULL","from":"2026-09-10"}
                        """))
                .andExpect(status().isOk());
    }
}
