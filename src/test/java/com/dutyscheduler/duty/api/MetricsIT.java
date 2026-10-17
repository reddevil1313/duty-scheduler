package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.persistence.AbsenceRepository;
import com.dutyscheduler.duty.persistence.DutySheetRepository;
import com.dutyscheduler.duty.persistence.TrooperEntity;
import com.dutyscheduler.duty.persistence.TrooperRepository;
import io.micrometer.core.instrument.MeterRegistry;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The app's own metrics move when the things they count happen. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "admin", roles = "ADMIN")
class MetricsIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    MeterRegistry registry;

    @Autowired
    TrooperRepository troopers;

    @Autowired
    DutySheetRepository sheets;

    @Autowired
    AbsenceRepository absences;

    @BeforeEach
    void roster() {
        sheets.deleteAll();
        absences.deleteAll();
        troopers.deleteAll();
        for (String name : List.of("TROOPER_A", "TROOPER_B", "TROOPER_C", "TROOPER_D", "TROOPER_E",
                "TROOPER_F", "TROOPER_G", "TROOPER_H")) {
            troopers.save(new TrooperEntity(name, false));
        }
        for (String name : List.of("TROOPER_I", "TROOPER_J", "TROOPER_K")) {
            troopers.save(new TrooperEntity(name, true));
        }
    }

    private long timed(String outcome) {
        var timer = registry.find("duty.sheet.generate").tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    private String generate(String date, String night) throws Exception {
        return mvc.perform(post("/api/schedules/generate").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"" + date + "\",\"nightGroup\":[" + night + "]}"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("making a sheet is timed, by whether it was solved, reused or could not be covered")
    void generateIsTimed() throws Exception {
        long solved = timed("solved"), reused = timed("reused"), unsolvable = timed("unsolvable");

        String sheet = generate("2026-09-10", "\"TROOPER_A\",\"TROOPER_B\",\"TROOPER_C\"");
        mvc.perform(put("/api/schedules/2026-09-10").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(sheet))
                .andExpect(status().isOk());
        generate("2026-09-17", "\"TROOPER_A\",\"TROOPER_B\",\"TROOPER_C\"");
        generate("2026-09-11", "\"TROOPER_A\"");

        assertThat(timed("solved")).isEqualTo(solved + 1);
        assertThat(timed("reused")).isEqualTo(reused + 1);
        assertThat(timed("unsolvable")).isEqualTo(unsolvable + 1);
        assertThat(registry.find("duty.sheet.generate").tag("turnout", "free").timer()).isNotNull();
    }

    @Test
    @DisplayName("logging a sheet is counted, by where it was logged from")
    void loggingIsCounted() throws Exception {
        var counter = registry.find("duty.sheet.logged").tag("via", "board").counter();
        double before = counter == null ? 0 : counter.count();

        String sheet = generate("2026-09-24", "\"TROOPER_A\",\"TROOPER_B\",\"TROOPER_C\"");
        mvc.perform(put("/api/schedules/2026-09-24").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(sheet))
                .andExpect(status().isOk());

        assertThat(registry.find("duty.sheet.logged").tag("via", "board").counter().count()).isEqualTo(before + 1);
    }
}
