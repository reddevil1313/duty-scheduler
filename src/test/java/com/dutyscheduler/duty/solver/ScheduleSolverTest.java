package com.dutyscheduler.duty.solver;

import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.AbsenceKind;
import com.dutyscheduler.duty.domain.DayDemand;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.DutyRules;
import com.dutyscheduler.duty.domain.Post;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;
import com.dutyscheduler.duty.rules.Rule;
import com.dutyscheduler.duty.rules.ScheduleValidator;
import com.dutyscheduler.duty.rules.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checked against the validator, not a fixed grid: there are many equally
 * legal answers, and the validator shares no code with the solver.
 */
class ScheduleSolverTest {

    // Not in roster order on purpose: HourTargets breaks ties by name, so
    // changing a letter changes a schedule.
    private static final Trooper TROOPER_E = Trooper.of("TROOPER_E");
    private static final Trooper TROOPER_C = Trooper.of("TROOPER_C");
    private static final Trooper TROOPER_J = Trooper.of("TROOPER_J");
    private static final Trooper TROOPER_B = Trooper.of("TROOPER_B");
    private static final Trooper TROOPER_I = Trooper.of("TROOPER_I");
    private static final Trooper TROOPER_K = Trooper.of("TROOPER_K");
    private static final Trooper TROOPER_H = Trooper.of("TROOPER_H");
    private static final Trooper TROOPER_F = Trooper.stayOut("TROOPER_F");
    private static final Trooper TROOPER_G = Trooper.stayOut("TROOPER_G");
    private static final Trooper TROOPER_D = Trooper.stayOut("TROOPER_D");
    private static final Trooper TROOPER_A = Trooper.of("TROOPER_A");

    private static final List<Trooper> ROSTER = List.of(
            TROOPER_E, TROOPER_C, TROOPER_J, TROOPER_B, TROOPER_I, TROOPER_K,
            TROOPER_H, TROOPER_F, TROOPER_G, TROOPER_D, TROOPER_A);
    private static final List<Trooper> NIGHT = List.of(TROOPER_E, TROOPER_C, TROOPER_J);

    private final ScheduleSolver solver = new ScheduleSolver();
    private final ScheduleValidator validator = new ScheduleValidator();

    @ParameterizedTest(name = "the {0} sheet solves and breaks no rule")
    @CsvSource({
            "2026-09-07, 47", // Monday
            "2026-09-08, 47",
            "2026-09-09, 47",
            "2026-09-10, 47",
            "2026-09-11, 44", // Friday: silent hours, then Saturday
            "2026-09-12, 48", // Saturday: AP and GG all day
            "2026-09-13, 51" // Sunday: weekday shape again from Mon 0600
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
    @DisplayName("every post manned, none extra")
    void coverageIsExact() {
        SolveResult result = solve(LocalDate.of(2026, 9, 10));

        ValidationResult check = validator.validate(result.schedule());

        assertFalse(check.has(Rule.UNDER_MANNED), check.describe());
        assertFalse(check.has(Rule.OVER_MANNED), check.describe());
    }

    @Test
    @DisplayName("everyone does exactly their target hours")
    void hoursMatchTheTargetsExactly() {
        SolveResult result = solve(LocalDate.of(2026, 9, 10));

        for (Trooper trooper : ROSTER) {
            assertEquals(result.targets().forTrooper(trooper), result.schedule().hours(trooper),
                    trooper.name() + " did not do their allocated hours");
        }
    }

    /** Night Troopers may only cross into the first few day hours. */
    @Test
    @DisplayName("night/day split holds apart from the crossover window")
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
                if (night) {
                    assertTrue(silent || hour < 6 + DutyRules.CROSS_WINDOW,
                            row.trooper().name() + " is on night but is up at " + day.slot(slot).label());
                } else {
                    assertFalse(silent,
                            row.trooper().name() + " is not on night but is up at " + day.slot(slot).label());
                }
            }
        }
    }

    @Test
    @DisplayName("no crossover on a full weekday")
    void aFullWeekdayKeepsTheHardPartition() {
        DutyDay day = DutyDay.of(LocalDate.of(2026, 9, 10));
        SolveResult result = solver.solve(SolveRequest.of(day, ROSTER, NIGHT));

        for (Trooper trooper : NIGHT) {
            Schedule.Row row = result.schedule().row(trooper).orElseThrow();
            for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
                if (row.at(slot) == null) {
                    continue;
                }
                int hour = day.slot(slot).start().getHour();
                assertTrue(hour >= 22 || hour < 6, trooper.name() + " at " + day.slot(slot).label());
            }
        }
    }

    /** Without crossover, 3 night Troopers get 5h each while 4 day Troopers get about 8h. */
    @Test
    @DisplayName("crossover evens out a thin day")
    void crossoverRescuesAThinDay() {
        List<Trooper> thin = List.of(TROOPER_E, TROOPER_C, TROOPER_J, TROOPER_B, TROOPER_I, TROOPER_H, TROOPER_A);

        SolveResult result = solver.solve(
                SolveRequest.of(DutyDay.of(LocalDate.of(2026, 9, 10)), thin, NIGHT));

        assertTrue(result.isSolved(), () -> "did not solve: " + result.reason());
        assertTrue(validator.validate(result.schedule()).ok());

        int fewest = result.schedule().rows().stream().mapToInt(Schedule.Row::hours).min().orElseThrow();
        int most = result.schedule().rows().stream().mapToInt(Schedule.Row::hours).max().orElseThrow();
        assertTrue(most - fewest <= 1, "hours ran " + fewest + " to " + most);
    }

    @Test
    @DisplayName("returns the best scoring sheet")
    void picksTheBestCandidateItFound() {
        SolveResult friday = solver.solve(SolveRequest.of(
                DutyDay.of(LocalDate.of(2026, 9, 11)), ROSTER, NIGHT));

        assertTrue(friday.isSolved());
        assertTrue(validator.validate(friday.schedule()).ok());
        // Friday: 16 silent hours on 3 Troopers, 28 day hours on 5. A spread of
        // 1 is the best possible.
        assertTrue(ScheduleScore.of(friday.schedule()).hourSpread() <= 1,
                ScheduleScore.of(friday.schedule()).toString());
    }

    @Test
    @DisplayName("no stint over three hours")
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
    @DisplayName("nobody is put on duty during leave")
    void solvesAroundLeave() {
        DutyDay day = DutyDay.of(LocalDate.of(2026, 9, 10));
        List<Absence> leave = List.of(
                Absence.fullDays(TROOPER_K, AbsenceKind.MC, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 12)),
                Absence.appointment(TROOPER_B, LocalDate.of(2026, 9, 11), LocalTime.of(9, 30), LocalTime.of(11, 30)),
                Absence.nightHalf(TROOPER_I, AbsenceKind.OL, LocalDate.of(2026, 9, 10)));

        SolveResult result = solver.solve(SolveRequest.of(day, ROSTER, NIGHT).withAbsences(leave));

        assertTrue(result.isSolved(), () -> "did not solve: " + result.reason());
        assertTrue(validator.validate(result.schedule()).ok());
        assertEquals(0, result.schedule().hours(TROOPER_K), "a Trooper on MC does nothing");

        Schedule.Row trooperB = result.schedule().row(TROOPER_B).orElseThrow();
        assertNull(trooperB.at(13), "0900: at their appointment");
        assertNull(trooperB.at(14), "1000");
        assertNull(trooperB.at(15), "1100");
        assertTrue(trooperB.hours() > 0, "but they still work the rest of the sheet");

        assertEquals(47, totalHours(result.schedule()));
    }

    @Test
    @DisplayName("deterministic")
    void isDeterministic() {
        DutyDay day = DutyDay.of(LocalDate.of(2026, 9, 10));

        SolveResult first = solver.solve(SolveRequest.of(day, ROSTER, NIGHT));
        SolveResult second = solver.solve(SolveRequest.of(day, ROSTER, NIGHT));

        assertEquals(first.schedule().rows(), second.schedule().rows());
    }

    private static Post[][] gridOf(Schedule schedule) {
        return schedule.rows().stream().map(r -> r.posts().toArray(new Post[0])).toArray(Post[][]::new);
    }

    private static int walks(Schedule schedule) {
        return schedule.rows().stream().mapToInt(r -> DutyRules.postChanges(r.posts().toArray(new Post[0]))).sum();
    }

    @ParameterizedTest(name = "the {0} sheet is left with no trade that would save a walk")
    @ValueSource(strings = {
            "2026-09-07", "2026-09-08", "2026-09-09", "2026-09-10",
            "2026-09-11", "2026-09-12", "2026-09-13" })
    void noWalkLeftToSave(String date) {
        Schedule sheet = solver.solve(SolveRequest.of(DutyDay.of(LocalDate.parse(date)), ROSTER, NIGHT)).schedule();

        Post[][] again = new PostSwapImprover().improve(gridOf(sheet));
        int after = java.util.Arrays.stream(again).mapToInt(DutyRules::postChanges).sum();
        assertEquals(walks(sheet), after);
    }

    @Test
    @DisplayName("a week has at most 35 AP/GG walks")
    void weekOfWalks() {
        // Was 53 before walks were scored. Weekday nights are stuck at 4. This
        // should only go down.
        int week = 0;
        for (int d = 7; d <= 13; d++) {
            week += walks(solver.solve(SolveRequest.of(DutyDay.of(LocalDate.of(2026, 9, d)), ROSTER, NIGHT)).schedule());
        }
        assertTrue(week <= 35, "the week walks " + week + " times between AP and GG");
    }

    @Test
    @DisplayName("history moves the spare hour, not the total")
    void historyShiftsWhoGetsTheExtraHour() {
        DutyDay day = DutyDay.of(LocalDate.of(2026, 9, 10));
        Map<Trooper, Integer> history = Map.of(TROOPER_E, 100, TROOPER_C, 90, TROOPER_J, 80);

        SolveResult result = solver.solve(
                SolveRequest.of(day, ROSTER, NIGHT).withHistory(history));

        assertTrue(result.isSolved());
        assertEquals(6, result.schedule().hours(TROOPER_J), "lowest cumulative hours takes the extra");
        assertEquals(5, result.schedule().hours(TROOPER_E));
        assertEquals(47, totalHours(result.schedule()));
    }

    @Test
    @DisplayName("refuses a night group too small for the silent hours")
    void shortNightGroupIsRefused() {
        SolveResult result = solver.solve(SolveRequest.of(
                DutyDay.of(LocalDate.of(2026, 9, 10)), ROSTER, List.of(TROOPER_E, TROOPER_C)));

        assertFalse(result.isSolved());
        assertTrue(result.reason().contains("at least 3"), result.reason());
    }

    @Test
    @DisplayName("refuses too few Troopers, with the numbers")
    void thinRosterIsRefused() {
        SolveResult result = solver.solve(SolveRequest.of(
                DutyDay.of(LocalDate.of(2026, 9, 10)),
                List.of(TROOPER_E, TROOPER_C, TROOPER_J, TROOPER_B), NIGHT));

        assertFalse(result.isSolved());
        assertTrue(result.reason().contains("47 Trooper-hours"), result.reason());
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
