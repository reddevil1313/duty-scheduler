package com.dutyscheduler.duty.persistence;

import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.AbsenceKind;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;
import com.dutyscheduler.duty.solver.ScheduleSolver;
import com.dutyscheduler.duty.solver.SolveRequest;
import com.dutyscheduler.duty.solver.SolveResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Against a real Postgres, in a container, with the real migrations applied.
 *
 * <p>
 * The obvious alternative is H2 in Postgres-compatibility mode, and it is a
 * trap. H2 does not have Postgres's type system, its CHECK constraints behave
 * differently, and half the SQL in V1 either fails or quietly means something
 * else. You end up with a green suite that tests a database you do not deploy —
 * and the first thing you find out in production is which of your constraints
 * were imaginary. A container costs a few seconds on the first run and tests
 * the
 * thing itself.
 *
 * <p>
 * Named {@code *IT} rather than {@code *Test} so it can be split off from the
 * fast unit suite later without renaming anything.
 */
@SpringBootTest
@Testcontainers
class DutySheetStoreIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    DutySheetStore store;

    @Autowired
    TrooperRepository troopers;

    @Autowired
    DutySheetRepository sheets;

    @Autowired
    DutyAssignmentRepository assignments;

    @Autowired
    AbsenceRepository absences;

    private static final List<String> ROSTER = List.of(
            "TROOPER_A", "TROOPER_B", "TROOPER_C", "TROOPER_D", "TROOPER_E", "TROOPER_F", "TROOPER_G", "TROOPER_H");
    private static final List<String> STAY_OUTS = List.of("TROOPER_I", "TROOPER_J", "TROOPER_K");
    private static final List<String> NIGHT = List.of("TROOPER_A", "TROOPER_B", "TROOPER_C");

    @BeforeEach
    void seedRoster() {
        sheets.deleteAll();
        absences.deleteAll();
        troopers.deleteAll();
        for (String name : ROSTER) {
            TrooperEntity entity = new TrooperEntity(name, false);
            entity.setOnNight(NIGHT.contains(name));
            troopers.save(entity);
        }
        for (String name : STAY_OUTS) {
            TrooperEntity entity = new TrooperEntity(name, true);
            entity.setPermPac(true);
            troopers.save(entity);
        }
    }

    private List<Trooper> roster() {
        return troopers.findAllByOrderByIdAsc().stream().map(TrooperEntity::toDomain).toList();
    }

    private List<Trooper> nightGroup() {
        return troopers.findByOnNightTrue().stream().map(TrooperEntity::toDomain).toList();
    }

    @Test
    @DisplayName("Flyway runs and the roster round-trips")
    void migrationsApplyAndTheRosterPersists() {
        assertEquals(11, troopers.count());
        assertTrue(troopers.findByName("TROOPER_I").orElseThrow().isStayOut());
        assertEquals(3, troopers.findByOnNightTrue().size());
    }

    @Test
    @DisplayName("a solved sheet goes in and comes back out identical")
    void scheduleRoundTrips() {
        DutyDay day = DutyDay.of(LocalDate.of(2026, 9, 10));
        SolveResult solved = new ScheduleSolver().solve(SolveRequest.of(day, roster(), nightGroup()));
        assertTrue(solved.isSolved(), () -> "did not solve: " + solved.reason());

        store.save(solved.schedule(), "admin");
        Schedule reloaded = store.load(day.date()).orElseThrow();

        assertEquals(day.date(), reloaded.day().date());
        for (Schedule.Row original : solved.schedule().rows()) {
            if (original.hours() == 0) {
                continue; // a man with nothing on has no rows to store
            }
            Schedule.Row back = reloaded.row(original.trooper()).orElseThrow(
                    () -> new AssertionError(original.trooper().name() + " did not come back"));
            for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
                assertEquals(original.at(slot), back.at(slot),
                        original.trooper().name() + " at slot " + slot);
            }
        }
    }

    @Test
    @DisplayName("logging the same day twice replaces it rather than doubling it")
    void savingIsAnUpsert() {
        DutyDay day = DutyDay.of(LocalDate.of(2026, 9, 10));
        Schedule first = new ScheduleSolver()
                .solve(SolveRequest.of(day, roster(), nightGroup())).schedule();

        store.save(first, "admin");
        long afterFirst = assignments.countForDate(day.date());
        store.save(first, "admin");
        long afterSecond = assignments.countForDate(day.date());

        assertEquals(47, afterFirst, "a weekday sheet is 47 post-hours");
        assertEquals(afterFirst, afterSecond, "re-logging a day must not duplicate its rows");
        assertEquals(1, sheets.count(), "and must not create a second sheet");
    }

    @Test
    @DisplayName("cumulative hours come back as a group-by, not a loop in Java")
    void hoursAggregateOverARange() {
        List<Trooper> roster = roster();
        List<Trooper> night = nightGroup();
        ScheduleSolver solver = new ScheduleSolver();
        for (int offset = 0; offset < 3; offset++) {
            DutyDay day = DutyDay.of(LocalDate.of(2026, 9, 7).plusDays(offset));
            store.save(solver.solve(SolveRequest.of(day, roster, night)).schedule(), "admin");
        }

        Map<String, Integer> hours = store.hoursSince(
                LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 9));
        Map<String, Integer> silent = store.silentHoursSince(
                LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 9));

        // Three weekday sheets at 47 post-hours each.
        assertEquals(47 * 3, hours.values().stream().mapToInt(Integer::intValue).sum());
        // The silent block is 16 ST-hours a night, and only the night group works it.
        assertEquals(16 * 3, silent.values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(NIGHT.containsAll(silent.keySet()), "only the night group did silent hours: " + silent);
    }

    @Test
    @DisplayName("leave round-trips and the 2000 rule survives the database")
    void absenceRoundTrips() {
        TrooperEntity trooper_f = troopers.findByName("TROOPER_F").orElseThrow();
        Absence mc = Absence.fullDays(trooper_f.toDomain(), AbsenceKind.MC,
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 12));
        absences.save(new AbsenceEntity(trooper_f, mc, "admin"));

        // The 11th's sheet is taken whole; the 12th's is not touched at all.
        assertEquals(1, store.absencesTouching(DutyDay.of(LocalDate.of(2026, 9, 11))).size());
        assertEquals(0, store.absencesTouching(DutyDay.of(LocalDate.of(2026, 9, 12))).size());

        Absence back = store.absencesTouching(DutyDay.of(LocalDate.of(2026, 9, 11))).get(0);
        assertEquals(AbsenceKind.MC, back.kind());
        assertEquals(LocalDate.of(2026, 9, 12).atTime(20, 0), back.endsAt());
    }

    @Test
    @DisplayName("the database refuses a man on two posts at the same hour")
    void theDatabaseEnforcesOnePostPerHour() {
        DutyDay day = DutyDay.of(LocalDate.of(2026, 9, 10));
        TrooperEntity trooper_a = troopers.findByName("TROOPER_A").orElseThrow();
        DutySheetEntity sheet = new DutySheetEntity(day.date(), "admin");
        sheet.add(new DutyAssignmentEntity(trooper_a, 5, com.dutyscheduler.duty.domain.Post.AP));
        sheet.add(new DutyAssignmentEntity(trooper_a, 5, com.dutyscheduler.duty.domain.Post.GG));

        assertThrows(Exception.class, () -> {
            sheets.save(sheet);
            sheets.flush();
        }, "the unique constraint on (sheet, trooper, slot) should have stopped this");
    }
}
