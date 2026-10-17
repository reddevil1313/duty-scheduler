package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.persistence.AbsenceRepository;
import com.dutyscheduler.duty.persistence.AppUserEntity;
import com.dutyscheduler.duty.persistence.AppUserRepository;
import com.dutyscheduler.duty.persistence.TeamEntity;
import com.dutyscheduler.duty.persistence.TeamRepository;
import com.dutyscheduler.duty.persistence.TeamScope;
import com.dutyscheduler.duty.persistence.TrooperEntity;
import com.dutyscheduler.duty.persistence.TrooperRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * No team can see or change another team's data. Both teams use the same
 * Trooper names. Alpha has data; Bravo only a roster. Uses real accounts,
 * since {@code @WithMockUser} has no team.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TeamIsolationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TeamRepository teams;

    @Autowired
    AppUserRepository users;

    @Autowired
    TrooperRepository troopers;

    @Autowired
    AbsenceRepository absences;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    DatabaseUserDetailsService accounts;

    static final String DATE = "2026-09-10";
    /** A week later, so Alpha's sheet could be reused. */
    static final String WEEK_ON = "2026-09-17";
    static final String GENERATE = """
            {"date":"%s","nightGroup":["TROOPER_A","TROOPER_B","TROOPER_C"]}
            """;

    long alpha;
    long bravo;
    List<Long> alphaIds;
    List<Long> bravoIds;
    long alphaAbsence;

    @BeforeEach
    void twoTeams() throws Exception {
        // Plain SQL: repository deletes are filtered to one team.
        jdbc.execute("TRUNCATE hour_credit, duty_assignment, duty_sheet, absence, trooper, app_user, team RESTART IDENTITY CASCADE");
        alpha = teams.save(new TeamEntity("Alpha")).getId();
        bravo = teams.save(new TeamEntity("Bravo")).getId();
        account("alpha_admin", "ADMIN", alpha);
        account("bravo_admin", "ADMIN", bravo);
        account("bravo_user", "USER", bravo);
        alphaIds = roster(alpha);
        bravoIds = roster(bravo);

        String sheet = body(as("alpha_admin", post("/api/schedules/generate")).content(GENERATE.formatted(DATE)));
        mvc.perform(as("alpha_admin", put("/api/schedules/" + DATE)).content(sheet)).andExpect(status().isOk());
        mvc.perform(as("alpha_admin", put("/api/schedules/" + DATE + "/credits")).content("""
                {"TROOPER_E":4}
                """)).andExpect(status().isNoContent());
        mvc.perform(as("alpha_admin", put("/api/records/opening")).content("""
                {"TROOPER_A":50}
                """)).andExpect(status().isOk());
        mvc.perform(as("alpha_admin", post("/api/absences")).content("""
                {"trooper":"TROOPER_D","kind":"MC","span":"FULL","from":"2026-09-10","to":"2026-09-12"}
                """)).andExpect(status().isCreated());
        mvc.perform(as("alpha_admin", put("/api/troopers/" + alphaIds.get(3))).content("""
                {"name":"TROOPER_D","stayOut":false,"onNight":false,"defaultBus":true}
                """)).andExpect(status().isOk());
        alphaAbsence = inTeam(alpha, () -> absences.findAll().getFirst().getId());
    }

    private void account(String username, String role, long team) {
        users.save(new AppUserEntity(username, username, encoder.encode("password"), role, team));
    }

    /** Same nine as the dev seed. */
    private List<Long> roster(long team) {
        return inTeam(team, () -> {
            List<Long> ids = new ArrayList<>();
            for (String name : List.of("TROOPER_A", "TROOPER_B", "TROOPER_C", "TROOPER_D", "TROOPER_E",
                    "TROOPER_F", "TROOPER_G", "TROOPER_H", "TROOPER_I")) {
                TrooperEntity t = new TrooperEntity(name, name.equals("TROOPER_H") || name.equals("TROOPER_I"));
                t.setOnNight(List.of("TROOPER_A", "TROOPER_B", "TROOPER_C").contains(name));
                ids.add(troopers.save(t).getId());
            }
            return ids;
        });
    }

    private static <T> T inTeam(long team, Supplier<T> work) {
        try (TeamScope.Opened ignored = TeamScope.open(team)) {
            return work.get();
        }
    }

    private MockHttpServletRequestBuilder as(String username, MockHttpServletRequestBuilder request) {
        RequestPostProcessor signedIn = user(accounts.loadUserByUsername(username));
        return request.with(signedIn).with(csrf()).contentType(MediaType.APPLICATION_JSON);
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("each team has its own roster")
    void rosters() throws Exception {
        mvc.perform(as("bravo_admin", get("/api/troopers")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(9)))
                .andExpect(jsonPath("$[*].defaultBus", everyItem(is(false))));
        String ids = body(as("bravo_user", get("/api/troopers")));
        for (Long id : alphaIds) {
            assertThat(ids).doesNotContain("\"id\":" + id + ",");
        }
        for (Long id : bravoIds) {
            assertThat(ids).contains("\"id\":" + id + ",");
        }
    }

    @Test
    @DisplayName("another team's sheet is a 404, also as an image")
    void sheets() throws Exception {
        mvc.perform(as("bravo_admin", get("/api/schedules/" + DATE))).andExpect(status().isNotFound());
        mvc.perform(as("bravo_admin", get("/api/schedules/" + DATE + "/image"))).andExpect(status().isNotFound());
        mvc.perform(as("bravo_admin", put("/api/schedules/" + DATE + "/credits")).content("{\"TROOPER_E\":2}"))
                .andExpect(status().isNotFound());
        // Alpha's is untouched.
        mvc.perform(as("alpha_admin", get("/api/schedules/" + DATE))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("two teams can log the same date")
    void sameEvening() throws Exception {
        String before = body(as("alpha_admin", get("/api/schedules/" + DATE)));
        String sheet = body(as("bravo_admin", post("/api/schedules/generate")).content(GENERATE.formatted(DATE)));
        mvc.perform(as("bravo_admin", put("/api/schedules/" + DATE)).content(sheet)).andExpect(status().isOk());

        assertThat(body(as("alpha_admin", get("/api/schedules/" + DATE)))).isEqualTo(before);
        mvc.perform(as("bravo_admin", get("/api/schedules/" + DATE))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("never reuses another team's sheet")
    void reuse() throws Exception {
        mvc.perform(as("alpha_admin", post("/api/schedules/generate")).content(GENERATE.formatted(WEEK_ON)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reusedFrom").value(DATE));
        mvc.perform(as("bravo_admin", post("/api/schedules/generate")).content(GENERATE.formatted(WEEK_ON)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reusedFrom").value(nullValue()));
    }

    @Test
    @DisplayName("another team's leave is hidden and can't be cancelled")
    void leave() throws Exception {
        mvc.perform(as("bravo_admin", get("/api/absences"))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(as("bravo_admin", delete("/api/absences/" + alphaAbsence))).andExpect(status().isNotFound());
        assertThat(inTeam(alpha, () -> absences.existsById(alphaAbsence))).isTrue();

        // Alpha's TROOPER_D is on MC this evening; Bravo's is not.
        String sheet = body(as("bravo_admin", post("/api/schedules/generate")).content(GENERATE.formatted(DATE)));
        mvc.perform(as("bravo_admin", post("/api/schedules/" + DATE + "/check")).content(sheet))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.away.TROOPER_D").doesNotExist());
        mvc.perform(as("alpha_admin", post("/api/schedules/" + DATE + "/check"))
                        .content(body(as("alpha_admin", get("/api/schedules/" + DATE)))))
                .andExpect(jsonPath("$.away.TROOPER_D").exists());
    }

    @Test
    @DisplayName("can't edit or remove another team's Trooper")
    void trooperById() throws Exception {
        long theirs = alphaIds.get(4);
        mvc.perform(as("bravo_admin", put("/api/troopers/" + theirs)).content("""
                {"name":"RENAMED","stayOut":false,"onNight":false}
                """)).andExpect(status().isNotFound());
        mvc.perform(as("bravo_admin", delete("/api/troopers/" + theirs))).andExpect(status().isNotFound());
        assertThat(inTeam(alpha, () -> troopers.findById(theirs)))
                .map(TrooperEntity::getName).hasValue("TROOPER_E");
        assertThat(inTeam(alpha, () -> troopers.findById(theirs))).map(TrooperEntity::isActive).hasValue(true);
    }

    @Test
    @DisplayName("records only count the team's own data")
    void records() throws Exception {
        mvc.perform(as("bravo_admin", get("/api/records")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days", hasSize(0)))
                .andExpect(jsonPath("$.troopers", hasSize(9)))
                .andExpect(jsonPath("$.troopers[*].total", everyItem(is(0))))
                .andExpect(jsonPath("$.troopers[*].mc", everyItem(is(0.0))));
        mvc.perform(as("alpha_admin", get("/api/records")))
                .andExpect(jsonPath("$.days[*].date", contains(DATE)))
                .andExpect(jsonPath("$.troopers[?(@.name == 'TROOPER_A')].opening", contains(50)))
                .andExpect(jsonPath("$.troopers[?(@.name == 'TROOPER_E')].credited", contains(4)));
    }

    @Test
    @DisplayName("default BUS is per team")
    void defaultBus() throws Exception {
        mvc.perform(as("bravo_admin", put("/api/troopers/" + bravoIds.get(4))).content("""
                {"name":"TROOPER_E","stayOut":false,"onNight":false,"defaultBus":true}
                """)).andExpect(status().isOk());

        mvc.perform(as("alpha_admin", get("/api/troopers")))
                .andExpect(jsonPath("$[?(@.defaultBus == true)].name", contains("TROOPER_D")));
        mvc.perform(as("bravo_admin", get("/api/troopers")))
                .andExpect(jsonPath("$[?(@.defaultBus == true)].name", contains("TROOPER_E")));
    }

    @Test
    @DisplayName("names are per team")
    void namesAreTheTeams() throws Exception {
        inTeam(alpha, () -> troopers.save(new TrooperEntity("TROOPER_Z", false)));
        mvc.perform(as("bravo_admin", post("/api/absences")).content("""
                {"trooper":"TROOPER_Z","kind":"MC","span":"FULL","from":"2026-09-10","to":"2026-09-10"}
                """)).andExpect(status().isBadRequest());
        // And Bravo may add a TROOPER_Z of its own.
        mvc.perform(as("bravo_admin", post("/api/troopers")).content("""
                {"name":"TROOPER_Z","stayOut":false,"onNight":false}
                """)).andExpect(status().isCreated());
        mvc.perform(as("bravo_admin", get("/api/troopers")))
                .andExpect(jsonPath("$[*].name", containsInAnyOrder("TROOPER_A", "TROOPER_B", "TROOPER_C",
                        "TROOPER_D", "TROOPER_E", "TROOPER_F", "TROOPER_G", "TROOPER_H", "TROOPER_I", "TROOPER_Z")));
    }

    @Test
    @DisplayName("the database rejects cross-team references")
    void databaseHoldsTheLine() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO absence (team_id, trooper_id, kind, span, from_date, to_date)
                VALUES (?, ?, 'MC', 'FULL', DATE '2026-09-10', DATE '2026-09-10')
                """, bravo, alphaIds.get(0)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("absence_trooper_same_team");
    }
}
