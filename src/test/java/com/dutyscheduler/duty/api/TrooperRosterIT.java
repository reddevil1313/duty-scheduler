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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Editing and removing Troopers, and above all what removal does to history.
 * Who may do it is {@link ApiSecurityIT}'s business; this is what happens when
 * an ADMIN does.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "admin", roles = "ADMIN")
class TrooperRosterIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    TrooperRepository troopers;

    @Autowired
    DutySheetRepository sheets;

    @Autowired
    AbsenceRepository absences;

    private static final String DATE = "2026-09-10";
    private static final List<String> NIGHT = List.of("TROOPER_A", "TROOPER_B", "TROOPER_C");

    @BeforeEach
    void seedRoster() {
        sheets.deleteAll();
        absences.deleteAll();
        troopers.deleteAll();
        for (String name : List.of("TROOPER_A", "TROOPER_B", "TROOPER_C", "TROOPER_D",
                "TROOPER_E", "TROOPER_F", "TROOPER_G", "TROOPER_H")) {
            TrooperEntity entity = new TrooperEntity(name, false);
            entity.setOnNight(NIGHT.contains(name));
            troopers.save(entity);
        }
        for (String name : List.of("TROOPER_I", "TROOPER_J", "TROOPER_K")) {
            troopers.save(new TrooperEntity(name, true));
        }
    }

    private long idOf(String name) {
        return troopers.findByName(name).orElseThrow().getId();
    }

    private static String body(String name, boolean stayOut, boolean onNight) {
        return """
                {"name":"%s","rank":"CPL","stayOut":%s,"onNight":%s}
                """.formatted(name, stayOut, onNight);
    }

    /** Generate the Thursday sheet and log it as used, so everyone on it has history. */
    private void logASheet() throws Exception {
        String sheet = mvc.perform(post("/api/schedules/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"date":"%s","nightGroup":["TROOPER_A","TROOPER_B","TROOPER_C"]}
                        """.formatted(DATE)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(put("/api/schedules/" + DATE).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(sheet))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an edit changes the particulars, and the Trooper keeps their place")
    void editKeepsPlace() throws Exception {
        long id = idOf("TROOPER_D");

        mvc.perform(put("/api/troopers/" + id).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("TROOPER_Z", true, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("TROOPER_Z"))
                .andExpect(jsonPath("$.rank").value("CPL"))
                .andExpect(jsonPath("$.stayOut").value(true));

        mvc.perform(get("/api/troopers"))
                .andExpect(jsonPath("$[3].name").value("TROOPER_Z"))
                .andExpect(jsonPath("$[*].name", not(hasItem("TROOPER_D"))));
    }

    @Test
    @DisplayName("a rename shows on the sheets they already stood, because sheets hold the id")
    void renameCarriesIntoHistory() throws Exception {
        logASheet();

        mvc.perform(put("/api/troopers/" + idOf("TROOPER_A")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("TROOPER_Z", false, true)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/schedules/" + DATE))
                .andExpect(jsonPath("$.rows[*].trooper", hasItem("TROOPER_Z")))
                .andExpect(jsonPath("$.rows[*].trooper", not(hasItem("TROOPER_A"))));
    }

    @Test
    @DisplayName("an edit is refused for a taken name (409), a stay-out on night (422), or nobody (404)")
    void editRefusals() throws Exception {
        long id = idOf("TROOPER_D");

        mvc.perform(put("/api/troopers/" + id).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("TROOPER_E", false, false)))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/troopers/" + id).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("TROOPER_D", true, true)))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(put("/api/troopers/999999").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("TROOPER_D", false, false)))
                .andExpect(status().isNotFound());

        assertThat(troopers.findById(id)).map(TrooperEntity::getName).hasValue("TROOPER_D");
    }

    @Test
    @DisplayName("keeping your own name is not a clash with yourself")
    void editWithSameName() throws Exception {
        mvc.perform(put("/api/troopers/" + idOf("TROOPER_D")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("TROOPER_D", false, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rank").value("CPL"));
    }

    @Test
    @DisplayName("removing a Trooper with no logged duty deletes them, and their leave with them")
    void removeWithoutHistoryDeletes() throws Exception {
        mvc.perform(post("/api/absences").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"trooper":"TROOPER_D","kind":"MC","span":"FULL","from":"2026-09-10","to":"2026-09-12"}
                        """))
                .andExpect(status().isCreated());

        mvc.perform(delete("/api/troopers/" + idOf("TROOPER_D")).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(troopers.findByName("TROOPER_D")).isEmpty();
        assertThat(absences.count()).isZero();
    }

    @Test
    @DisplayName("removing a Trooper who stood a logged sheet archives them, and the sheet is untouched")
    void removeWithHistoryArchives() throws Exception {
        logASheet();
        long id = idOf("TROOPER_A");

        mvc.perform(delete("/api/troopers/" + id).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(troopers.findById(id)).map(TrooperEntity::isActive).hasValue(false);
        mvc.perform(get("/api/troopers"))
                .andExpect(jsonPath("$[*].name", not(hasItem("TROOPER_A"))));
        mvc.perform(get("/api/schedules/" + DATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].trooper", hasItem("TROOPER_A")));

        // Gone means gone: a second remove, an edit, or a booking all miss.
        mvc.perform(delete("/api/troopers/" + id).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(put("/api/troopers/" + id).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("TROOPER_A", false, false)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/absences").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"trooper":"TROOPER_A","kind":"MC","span":"FULL","from":"2026-09-11","to":"2026-09-12"}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an archived Trooper is never scheduled, even with leave still on the books")
    void archivedIsNeverScheduled() throws Exception {
        logASheet();
        mvc.perform(post("/api/absences").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"trooper":"TROOPER_D","kind":"MC","span":"FULL","from":"2026-09-11","to":"2026-09-13"}
                        """))
                .andExpect(status().isCreated());
        mvc.perform(delete("/api/troopers/" + idOf("TROOPER_D")).with(csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/schedules/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"date":"2026-09-11","nightGroup":["TROOPER_A","TROOPER_B","TROOPER_C"]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].trooper", not(hasItem("TROOPER_D"))));
    }

    @Test
    @DisplayName("adding an archived Trooper's name brings them back, history and all")
    void addingBackRevives() throws Exception {
        logASheet();
        long id = idOf("TROOPER_A");
        mvc.perform(delete("/api/troopers/" + id).with(csrf())).andExpect(status().isNoContent());

        mvc.perform(post("/api/troopers").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("TROOPER_A", false, true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.onNight").value(true));

        assertThat(troopers.findById(id)).map(TrooperEntity::isActive).hasValue(true);
    }

    @Test
    @DisplayName("renaming someone to an archived Trooper's name is a 409 that says what to do instead")
    void renameOntoArchivedName() throws Exception {
        logASheet();
        mvc.perform(delete("/api/troopers/" + idOf("TROOPER_A")).with(csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(put("/api/troopers/" + idOf("TROOPER_D")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("TROOPER_A", false, false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "TROOPER_A belongs to an archived Trooper. Add them back instead."));
    }
}
