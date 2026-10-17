package com.dutyscheduler.duty.solver;

import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.AbsenceKind;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Post;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;
import com.dutyscheduler.duty.rules.ScheduleValidator;
import com.dutyscheduler.duty.rules.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A chosen BUS Trooper is pinned, or refused up front. */
class BusAssignmentTest {

    private static final Trooper TROOPER_A = Trooper.of("TROOPER_A");
    private static final Trooper TROOPER_B = Trooper.of("TROOPER_B");
    private static final Trooper TROOPER_C = Trooper.of("TROOPER_C");
    private static final Trooper TROOPER_D = Trooper.of("TROOPER_D");
    private static final Trooper TROOPER_E = Trooper.of("TROOPER_E");
    private static final Trooper TROOPER_F = Trooper.of("TROOPER_F");
    private static final Trooper TROOPER_G = Trooper.of("TROOPER_G");
    private static final Trooper TROOPER_H = Trooper.stayOut("TROOPER_H");
    private static final Trooper TROOPER_I = Trooper.stayOut("TROOPER_I");

    /** Same nine as the dev seed. */
    private static final List<Trooper> ROSTER = List.of(
            TROOPER_A, TROOPER_B, TROOPER_C, TROOPER_D, TROOPER_E, TROOPER_F, TROOPER_G, TROOPER_H, TROOPER_I);
    private static final List<Trooper> NIGHT = List.of(TROOPER_A, TROOPER_B, TROOPER_C);

    private static final DutyDay THURSDAY = DutyDay.of(LocalDate.of(2026, 9, 10));

    private final ScheduleSolver solver = new ScheduleSolver();
    private final ScheduleValidator validator = new ScheduleValidator();

    private static Trooper named(String name) {
        return ROSTER.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    /** Who stands BUS at each hour it stands. */
    private static List<String> busCrew(Schedule sheet) {
        return sheet.rows().stream()
                .flatMap(r -> r.posts().stream().filter(p -> p == Post.BUS).map(p -> r.trooper().name()))
                .toList();
    }

    @ParameterizedTest(name = "{0} chosen: every BUS hour is theirs, and the sheet is legal")
    @ValueSource(strings = { "TROOPER_D", "TROOPER_E", "TROOPER_F", "TROOPER_G" })
    void chosenTrooperTakesBus(String name) {
        Trooper chosen = named(name);
        SolveResult result = solver.solve(SolveRequest.of(THURSDAY, ROSTER, NIGHT).withBus(chosen));

        assertTrue(result.isSolved(), () -> "did not solve: " + result.reason());
        assertEquals(List.of(name, name, name), busCrew(result.schedule()), "0700, 0800 and 0900");
        ValidationResult check = validator.validate(result.schedule());
        assertTrue(check.ok(), check::describe);
    }

    @Test
    @DisplayName("BUS hours count toward the target")
    void busCountsTowardTheTarget() {
        SolveResult chosen = solver.solve(SolveRequest.of(THURSDAY, ROSTER, NIGHT).withBus(TROOPER_D));

        // A pinned BUS can change which crossover wins, so only check it's included.
        assertEquals(chosen.targets().forTrooper(TROOPER_D), chosen.schedule().hours(TROOPER_D));
        assertEquals(47, chosen.schedule().rows().stream().mapToInt(Schedule.Row::hours).sum());
    }

    @Test
    @DisplayName("a night Trooper can take BUS")
    void nightTrooperCanTakeBus() {
        SolveResult result = solver.solve(SolveRequest.of(THURSDAY, ROSTER, NIGHT).withBus(TROOPER_A));

        assertTrue(result.isSolved(), () -> "did not solve: " + result.reason());
        assertEquals(List.of("TROOPER_A", "TROOPER_A", "TROOPER_A"), busCrew(result.schedule()));
        assertTrue(validator.validate(result.schedule()).ok());
    }

    @Test
    @DisplayName("stay-out BUS is refused")
    void stayOutIsRefused() {
        SolveResult result = solver.solve(SolveRequest.of(THURSDAY, ROSTER, NIGHT).withBus(TROOPER_H));

        assertFalse(result.isSolved());
        assertEquals("TROOPER_H cannot take BUS: they are not available at 0700, being a stay-out.", result.reason());
        assertEquals(0, result.nodes(), "refused before searching");
    }

    @Test
    @DisplayName("BUS on leave is refused")
    void leaveIsRefused() {
        // 0830 on the 11th.
        Absence appointment = Absence.appointment(TROOPER_D, LocalDate.of(2026, 9, 11),
                LocalTime.of(8, 30), LocalTime.of(9, 0));
        SolveResult result = solver.solve(SolveRequest.of(THURSDAY, ROSTER, NIGHT)
                .withAbsences(List.of(appointment)).withBus(TROOPER_D));

        assertFalse(result.isSolved());
        assertEquals("TROOPER_D cannot take BUS: they are not available at 0800, being on leave.", result.reason());
        assertEquals(0, result.nodes());
    }

    @Test
    @DisplayName("no BUS on the sheet: choice ignored")
    void noBusNoEffect() {
        DutyDay saturday = DutyDay.of(LocalDate.of(2026, 9, 12));
        SolveResult free = solver.solve(SolveRequest.of(saturday, ROSTER, NIGHT));
        SolveResult chosen = solver.solve(SolveRequest.of(saturday, ROSTER, NIGHT).withBus(TROOPER_D));

        assertTrue(chosen.isSolved(), () -> "did not solve: " + chosen.reason());
        assertEquals(free.schedule().rows(), chosen.schedule().rows());
    }

    @Test
    @DisplayName("no choice: the solver covers BUS")
    void unchosenStillCovered() {
        // It may split the run.
        SolveResult result = solver.solve(SolveRequest.of(THURSDAY, ROSTER, NIGHT));
        assertEquals(3, busCrew(result.schedule()).size(), "0700, 0800 and 0900 are all manned");
        assertTrue(validator.validate(result.schedule()).ok());
    }

    @Test
    @DisplayName("improvers never move a pinned BUS hour")
    void improversLeaveThePinAlone() {

        for (int d = 6; d <= 10; d++) {
            DutyDay day = DutyDay.of(LocalDate.of(2026, 9, d));
            SolveResult result = solver.solve(SolveRequest.of(day, ROSTER, NIGHT).withBus(TROOPER_G));
            assertTrue(result.isSolved(), () -> day + " did not solve: " + result.reason());
            assertTrue(busCrew(result.schedule()).stream().allMatch("TROOPER_G"::equals), day + " " + busCrew(result.schedule()));
        }
    }

    @Test
    @DisplayName("BUS must be on the roster")
    void busMustBeOnRoster() {
        assertThrows(IllegalArgumentException.class,
                () -> SolveRequest.of(THURSDAY, ROSTER, NIGHT).withBus(Trooper.of("NOBODY")));
    }
}
