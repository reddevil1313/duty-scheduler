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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Editing and removing Troopers, and what removal does to history. */
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

    /** Logs Thursday so everyone on it has history. */
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
    @DisplayName("edit keeps roster position")
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
    @DisplayName("rename shows on old sheets")
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
    @DisplayName("edit refusals: 409, 422, 404")
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
    @DisplayName("keeping the same name is fine")
    void editWithSameName() throws Exception {
        mvc.perform(put("/api/troopers/" + idOf("TROOPER_D")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("TROOPER_D", false, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rank").value("CPL"));
    }

    @Test
    @DisplayName("no logged duty: deleted with their leave")
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
    @DisplayName("logged duty: archived, sheet untouched")
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
    @DisplayName("archived Troopers are never scheduled")
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
    @DisplayName("re-adding an archived name restores them")
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

    private void setDefaultBus(String name, boolean on) throws Exception {
        TrooperEntity t = troopers.findByName(name).orElseThrow();
        mvc.perform(put("/api/troopers/" + t.getId()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"%s","stayOut":%s,"onNight":%s,"defaultBus":%s}
                        """.formatted(name, t.isStayOut(), t.isOnNight(), on)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("only one default BUS")
    void oneDefaultBus() throws Exception {
        setDefaultBus("TROOPER_D", true);
        mvc.perform(get("/api/troopers"))
                .andExpect(jsonPath("$[?(@.defaultBus == true)].name", contains("TROOPER_D")));

        setDefaultBus("TROOPER_E", true);
        mvc.perform(get("/api/troopers"))
                .andExpect(jsonPath("$[?(@.defaultBus == true)].name", contains("TROOPER_E")));

        setDefaultBus("TROOPER_E", false);
        mvc.perform(get("/api/troopers"))
                .andExpect(jsonPath("$[?(@.defaultBus == true)]").isEmpty());
    }

    @Test
    @DisplayName("edit without defaultBus keeps it")
    void editKeepsDefaultBus() throws Exception {
        setDefaultBus("TROOPER_D", true);
        mvc.perform(put("/api/troopers/" + idOf("TROOPER_D")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("TROOPER_D", false, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultBus").value(true));
    }

    @Test
    @DisplayName("stay-outs and archived Troopers lose default BUS")
    void stayOutAndArchiveClearIt() throws Exception {
        mvc.perform(put("/api/troopers/" + idOf("TROOPER_I")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"TROOPER_I","stayOut":true,"onNight":false,"defaultBus":true}
                        """))
                .andExpect(status().isUnprocessableContent());

        setDefaultBus("TROOPER_D", true);
        mvc.perform(put("/api/troopers/" + idOf("TROOPER_D")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("TROOPER_D", true, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultBus").value(false));

        setDefaultBus("TROOPER_E", true);
        logASheet();
        mvc.perform(delete("/api/troopers/" + idOf("TROOPER_E")).with(csrf())).andExpect(status().isNoContent());
        assertThat(troopers.findByName("TROOPER_E")).map(TrooperEntity::isDefaultBus).hasValue(false);
    }

    @Test
    @DisplayName("new Trooper can take default BUS")
    void addAsDefaultBus() throws Exception {
        setDefaultBus("TROOPER_D", true);
        mvc.perform(post("/api/troopers").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"TROOPER_L","stayOut":false,"onNight":false,"defaultBus":true}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.defaultBus").value(true));
        assertThat(troopers.findByName("TROOPER_D")).map(TrooperEntity::isDefaultBus).hasValue(false);
    }

    @Test
    @DisplayName("renaming to an archived name is a 409")
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
