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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** An image means the sheet is logged; a failure logs nothing. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "admin", roles = "ADMIN")
class SheetImageIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final String DATE = "2026-09-10";
    private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

    @Autowired
    MockMvc mvc;

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

    private String generate(String extra) throws Exception {
        return mvc.perform(post("/api/schedules/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"date":"%s","nightGroup":["TROOPER_A","TROOPER_B","TROOPER_C"]%s}
                        """.formatted(DATE, extra)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static boolean isPng(byte[] bytes) {
        if (bytes.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    @Test
    @DisplayName("returns a PNG and logs the sheet")
    void imageLogs() throws Exception {
        String sheet = generate(",\"turnout\":5").replaceFirst("\"turnout\":null", "\"turnout\":5");

        byte[] png = mvc.perform(post("/api/schedules/" + DATE + "/image").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(sheet))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"duty-sheet-" + DATE + ".png\""))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(isPng(png)).isTrue();
        mvc.perform(get("/api/schedules/" + DATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turnout").value(5))
                .andExpect(jsonPath("$.totalHours").value(46));
    }

    @Test
    @DisplayName("no image if it can't be logged")
    void failureLogsNothing() throws Exception {
        String sheet = generate("").replace("TROOPER_D", "NOBODY");

        mvc.perform(post("/api/schedules/" + DATE + "/image").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(sheet))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/schedules/" + DATE)).andExpect(status().isNotFound());
        assertThat(sheets.existsByDutyDate(LocalDate.parse(DATE))).isFalse();
    }

    @Test
    @DisplayName("any user can download a logged sheet's image")
    void imageOfALoggedSheet() throws Exception {
        mvc.perform(post("/api/schedules/" + DATE + "/image").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(generate("")))
                .andExpect(status().isOk());

        byte[] again = mvc.perform(get("/api/schedules/" + DATE + "/image").with(user("trooper_d").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(isPng(again)).isTrue();
    }

    @Test
    @DisplayName("no image for an unlogged day")
    void noImageWithoutALog() throws Exception {
        mvc.perform(get("/api/schedules/" + DATE + "/image")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a Trooper can't make the image")
    void usersCannotLogByImage() throws Exception {
        String sheet = generate("");
        mvc.perform(post("/api/schedules/" + DATE + "/image").with(csrf()).with(user("trooper_d").roles("USER"))
                .contentType(MediaType.APPLICATION_JSON).content(sheet))
                .andExpect(status().isForbidden());
        assertThat(sheets.existsByDutyDate(LocalDate.parse(DATE))).isFalse();
    }
}
