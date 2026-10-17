package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.persistence.AppUserEntity;
import com.dutyscheduler.duty.persistence.AppUserRepository;
import com.dutyscheduler.duty.persistence.TeamEntity;
import com.dutyscheduler.duty.persistence.TeamRepository;
import com.dutyscheduler.duty.persistence.TeamScope;
import com.dutyscheduler.duty.persistence.TrooperEntity;
import com.dutyscheduler.duty.persistence.TrooperRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The seed is only worth having if it is usable: accounts that sign in through
 * the real login filter, and a roster the solver can actually cover. A row with
 * a hash the encoder cannot match, or a roster one Trooper short at the
 * weekend, would look seeded and still leave a fresh database stuck.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class DevDataSeederIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    AppUserRepository users;

    @Autowired
    TrooperRepository troopers;

    @Autowired
    ApplicationRunner seedDevData;

    @Autowired
    TeamRepository teams;

    @Autowired
    DatabaseUserDetailsService accounts;

    private TeamScope.Opened devTeam;

    /**
     * Read the roster as the dev team, which is where the seeder put it; the
     * test team every other test runs as has none.
     */
    @BeforeEach
    void openDevTeam() {
        devTeam = TeamScope.open(users.findByUsername("admin").orElseThrow().getTeamId());
    }

    @AfterEach
    void closeDevTeam() {
        devTeam.close();
    }

    @Test
    @DisplayName("both dev accounts sign in with their default passwords")
    void seededAccountsSignIn() throws Exception {
        mvc.perform(post("/api/session/login").with(csrf())
                .param("username", "admin").param("password", "admin"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/session/login").with(csrf())
                .param("username", "trooper_d").param("password", "trooper_d"))
                .andExpect(status().isNoContent());

        assertThat(users.findByUsername("admin")).map(AppUserEntity::getRole).hasValue("ADMIN");
        assertThat(users.findByUsername("trooper_d")).map(AppUserEntity::getRole).hasValue("USER");
    }

    @Test
    @DisplayName("both accounts belong to the dev team")
    void seededTeam() {
        Long team = users.findByUsername("admin").orElseThrow().getTeamId();
        assertThat(teams.findById(team)).map(TeamEntity::getName).hasValue(DevDataSeeder.TEAM);
        assertThat(users.findByUsername("trooper_d")).map(AppUserEntity::getTeamId).hasValue(team);
    }

    @Test
    @DisplayName("the roster is nine Troopers: A to C on night, H and I staying out")
    void seededRoster() {
        List<TrooperEntity> roster = troopers.findAllByOrderByIdAsc();

        assertThat(roster).extracting(TrooperEntity::getName).containsExactly(
                "TROOPER_A", "TROOPER_B", "TROOPER_C", "TROOPER_D", "TROOPER_E",
                "TROOPER_F", "TROOPER_G", "TROOPER_H", "TROOPER_I");
        assertThat(roster).filteredOn(TrooperEntity::isOnNight).extracting(TrooperEntity::getName)
                .containsExactly("TROOPER_A", "TROOPER_B", "TROOPER_C");
        assertThat(roster).filteredOn(TrooperEntity::isStayOut).extracting(TrooperEntity::getName)
                .containsExactly("TROOPER_H", "TROOPER_I");
    }

    @ParameterizedTest(name = "the {0} sheet can be generated from the seed alone")
    @ValueSource(strings = {
            "2026-09-07", "2026-09-08", "2026-09-09", "2026-09-10",
            "2026-09-11", "2026-09-12", "2026-09-13" })
    void everyDayOfTheWeekSolves(String date) throws Exception {
        // The seeded admin itself, so the request runs as the dev team.
        mvc.perform(post("/api/schedules/generate").with(csrf()).with(user(accounts.loadUserByUsername("admin")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"date":"%s","nightGroup":["TROOPER_A","TROOPER_B","TROOPER_C"]}
                        """.formatted(date)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a restart changes nothing: no duplicates, no reset password, no returning Trooper")
    void seedingAgainChangesNothing() throws Exception {
        AppUserEntity admin = users.findByUsername("admin").orElseThrow();
        String originalHash = admin.getPasswordHash();
        admin.setPasswordHash("changed-by-hand");
        users.save(admin);
        TrooperEntity removed = troopers.findByName("TROOPER_I").orElseThrow();
        troopers.delete(removed);
        long accountsBefore = users.count();
        long rosterBefore = troopers.count();
        try {
            seedDevData.run(new DefaultApplicationArguments());

            assertThat(users.count()).isEqualTo(accountsBefore);
            assertThat(users.findByUsername("admin")).map(AppUserEntity::getPasswordHash)
                    .hasValue("changed-by-hand");
            assertThat(troopers.count()).isEqualTo(rosterBefore);
            assertThat(troopers.findByName("TROOPER_I")).isEmpty();
        } finally {
            // The container is shared with the other tests here, which run in no
            // fixed order relative to this one.
            admin.setPasswordHash(originalHash);
            users.save(admin);
            TrooperEntity restored = new TrooperEntity("TROOPER_I", true);
            troopers.save(restored);
        }
    }
}
