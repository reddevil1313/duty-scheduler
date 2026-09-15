package com.dutyscheduler.duty.solver;

import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.AbsenceKind;
import com.dutyscheduler.duty.domain.DayDemand;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.DutyRules;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;
import com.dutyscheduler.duty.rules.Rule;
import com.dutyscheduler.duty.rules.ScheduleValidator;
import com.dutyscheduler.duty.rules.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The search is tested against the validator, not against a fixed expected grid.
 *
 * <p>That distinction matters. Asserting on a particular arrangement would pin
 * one arbitrary answer out of thousands of equally legal ones, and every future
 * change to the search would break it for no reason. Asserting that the answer
 * passes an independently written rule checker tests the thing that is actually
 * required — and the checker shares no code with the search, so it cannot agree
 * with a bug by construction.
 */
class ScheduleSolverTest {

    private static final Trooper TROOPER_A = Trooper.of("TROOPER_A");
    private static final Trooper TROOPER_B = Trooper.of("TROOPER_B");
    private static final Trooper TROOPER_C = Trooper.of("TROOPER_C");
    private static final Trooper TROOPER_D = Trooper.of("TROOPER_D");
    private static final Trooper TROOPER_E = Trooper.of("TROOPER_E");
    private static final Trooper TROOPER_F = Trooper.of("TROOPER_F");
    private static final Trooper TROOPER_G = Trooper.of("TROOPER_G");
    private static final Trooper TROOPER_H = Trooper.stayOut("TROOPER_H");
    private static final Trooper TROOPER_I = Trooper.stayOut("TROOPER_I");
    private static final Trooper TROOPER_J = Trooper.stayOut("TROOPER_J");
    private static final Trooper TROOPER_K = Trooper.of("TROOPER_K");

    private static final List<Trooper> ROSTER = List.of(
            TROOPER_A, TROOPER_B, TROOPER_C, TROOPER_D, TROOPER_E, TROOPER_F, TROOPER_G, TROOPER_H, TROOPER_I, TROOPER_J, TROOPER_K);
    private static final List<Trooper> NIGHT = List.of(TROOPER_A, TROOPER_B, TROOPER_C);

    private final ScheduleSolver solver = new ScheduleSolver();
    private final ScheduleValidator validator = new ScheduleValidator();

    @ParameterizedTest(name = "the {0} sheet solves and breaks no rule")
    @CsvSource({
            "2026-09-07, 47",   // Monday
            "2026-09-08, 47",
            "2026-09-09, 47",
            "2026-09-10, 47",
            "2026-09-11, 44",   // Friday — silent hours, then a Saturday
            "2026-09-12, 48",   // Saturday — AP and GG right through
            "2026-09-13, 51"    // Sunday — hands back to the weekday shape at Mon 0600
    })
    @DisplayName("every day of the week")
    void solvesEveryDayOfTheWeek(String date, int expectedHours) {
        DutyDay day = DutyDay.of(LocalDate.parse(date));

        SolveResult result = solver.solve(SolveRequest.of(day, ROSTER, NIGHT));

        assertTrue(result.isSolved(), () -> date + " did not solve: " + result.reason());
        ValidationResult check = validator.validate(result.schedule());
        assertTrue(check.ok(), () -> date + " broke a rule:\n" + check.describe());
        assertEquals(expectedHours, totalHours(result.schedule()));
        assertEquals(expectedHours, DayDemand.of(day).postHours(), "and that is the sheet's own demand");
    }

    @Test
    @DisplayName("every post is manned and nobody stands one that is not required")
    void coverageIsExact() {
        SolveResult result = solve(LocalDate.of(2026, 9, 10));

        ValidationResult check = validator.validate(result.schedule());

        assertFalse(check.has(Rule.UNDER_MANNED), check.describe());
        assertFalse(check.has(Rule.OVER_MANNED), check.describe());
    }

    @Test
    @DisplayName("everybody does exactly the hours he was allocated, to the hour")
    void hoursMatchTheTargetsExactly() {
        SolveResult result = solve(LocalDate.of(2026, 9, 10));

        for (Trooper trooper : ROSTER) {
            assertEquals(result.targets().forTrooper(trooper), result.schedule().hours(trooper),
                    trooper.name() + " did not do his allocated hours");
        }
    }

    @Test
    @DisplayName("the night group takes the silent hours and nobody else goes near them")
    void theNightDayPartitionHolds() {
        DutyDay day = DutyDay.of(LocalDate.of(2026, 9, 10));
        SolveResult result = solver.solve(SolveRequest.of(day, ROSTER, NIGHT));

        for (Schedule.Row row : result.schedule().rows()) {
            boolean night = NIGHT.contains(row.trooper());
            for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
                if (row.at(slot) == null) {
                    continue;
                }
                int hour = day.slot(slot).start().getHour();
                boolean silent = hour >= 22 || hour < 6;
                assertEquals(night, silent,
                        row.trooper().name() + " is on at " + day.slot(slot).label()
                                + " but " + (night ? "is" : "is not") + " on night");
            }
        }
    }

    @Test
    @DisplayName("nobody is on for more than three hours in a row")
    void stintsAreRespected() {
        SolveResult result = solve(LocalDate.of(2026, 9, 13));

        for (Schedule.Row row : result.schedule().rows()) {
            int run = 0;
            for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
                run = row.at(slot) == null ? 0 : run + 1;
                assertTrue(run <= DutyRules.MAX_STINT,
                        row.trooper().name() + " is on for " + run + " hours in a row");
            }
        }
    }

    @Test
    @DisplayName("leave is worked around rather than worked through")
    void solvesAroundLeave() {
        DutyDay day = DutyDay.of(LocalDate.of(2026, 9, 10));
        List<Absence> leave = List.of(
                Absence.fullDays(TROOPER_F, AbsenceKind.MC, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 12)),
                Absence.appointment(TROOPER_D, LocalDate.of(2026, 9, 11), LocalTime.of(9, 30), LocalTime.of(11, 30)),
                Absence.nightHalf(TROOPER_E, AbsenceKind.OL, LocalDate.of(2026, 9, 10)));

        SolveResult result = solver.solve(SolveRequest.of(day, ROSTER, NIGHT).withAbsences(leave));

        assertTrue(result.isSolved(), () -> "did not solve: " + result.reason());
        assertTrue(validator.validate(result.schedule()).ok());
        assertEquals(0, result.schedule().hours(TROOPER_F), "a man on MC does nothing");

        Schedule.Row TROOPER_D_ROW = result.schedule().row(TROOPER_D).orElseThrow();
        assertNull(TROOPER_D_ROW.at(13), "0900 — he is at his appointment");
        assertNull(TROOPER_D_ROW.at(14), "1000");
        assertNull(TROOPER_D_ROW.at(15), "1100");
        assertTrue(TROOPER_D_ROW.hours() > 0, "but he still works the rest of the sheet");

        // The sheet still gets covered; the hours just land on other people.
        assertEquals(47, totalHours(result.schedule()));
    }

    @Test
    @DisplayName("the same request twice gives the same sheet")
    void isDeterministic() {
        DutyDay day = DutyDay.of(LocalDate.of(2026, 9, 10));

        SolveResult first = solver.solve(SolveRequest.of(day, ROSTER, NIGHT));
        SolveResult second = solver.solve(SolveRequest.of(day, ROSTER, NIGHT));

        assertEquals(first.schedule().rows(), second.schedule().rows());
    }

    @Test
    @DisplayName("history moves the odd hour without changing the total")
    void historyShiftsWhoGetsTheExtraHour() {
        DutyDay day = DutyDay.of(LocalDate.of(2026, 9, 10));
        Map<Trooper, Integer> history = Map.of(TROOPER_A, 100, TROOPER_B, 90, TROOPER_C, 80);

        SolveResult result = solver.solve(
                SolveRequest.of(day, ROSTER, NIGHT).withHistory(history));

        assertTrue(result.isSolved());
        assertEquals(6, result.schedule().hours(TROOPER_C), "lowest cumulative hours takes the extra");
        assertEquals(5, result.schedule().hours(TROOPER_A));
        assertEquals(47, totalHours(result.schedule()));
    }

    @Test
    @DisplayName("a night group that cannot hold the silent hours is refused, and says so")
    void shortNightGroupIsRefused() {
        SolveResult result = solver.solve(SolveRequest.of(
                DutyDay.of(LocalDate.of(2026, 9, 10)), ROSTER, List.of(TROOPER_A, TROOPER_B)));

        assertFalse(result.isSolved());
        assertTrue(result.reason().contains("at least 3"), result.reason());
    }

    @Test
    @DisplayName("too few men is refused with the arithmetic, not a shrug")
    void thinRosterIsRefused() {
        SolveResult result = solver.solve(SolveRequest.of(
                DutyDay.of(LocalDate.of(2026, 9, 10)),
                List.of(TROOPER_A, TROOPER_B, TROOPER_C, TROOPER_D), NIGHT));

        assertFalse(result.isSolved());
        assertTrue(result.reason().contains("47 ST-hours"), result.reason());
    }

    private SolveResult solve(LocalDate date) {
        SolveResult result = solver.solve(SolveRequest.of(DutyDay.of(date), ROSTER, NIGHT));
        assertTrue(result.isSolved(), () -> "did not solve: " + result.reason());
        return result;
    }

    private static int totalHours(Schedule schedule) {
        return schedule.rows().stream().mapToInt(Schedule.Row::hours).sum();
    }
}
