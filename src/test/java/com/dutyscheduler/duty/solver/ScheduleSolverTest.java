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
 * The search is tested against the validator, not against a fixed expected
 * grid.
 *
 * <p>
 * That distinction matters. Asserting on a particular arrangement would pin
 * one arbitrary answer out of thousands of equally legal ones, and every future
 * change to the search would break it for no reason. Asserting that the answer
 * passes an independently written rule checker tests the thing that is actually
 * required — and the checker shares no code with the search, so it cannot agree
 * with a bug by construction.
 */
class ScheduleSolverTest {

    private static final Trooper JONAS = Trooper.of("JONAS");
    private static final Trooper EDISON = Trooper.of("EDISON");
    private static final Trooper SUN_FONG = Trooper.of("SUN FONG");
    private static final Trooper BRYAN = Trooper.of("BRYAN");
    private static final Trooper SRIRAM = Trooper.of("SRIRAM");
    private static final Trooper ZUL = Trooper.of("ZUL");
    private static final Trooper SAMAY = Trooper.of("SAMAY");
    private static final Trooper RAIDEN = Trooper.stayOut("RAIDEN");
    private static final Trooper RAYAAN = Trooper.stayOut("RAYAAN");
    private static final Trooper HAOYI = Trooper.stayOut("HAOYI");
    private static final Trooper AHMAD = Trooper.of("AHMAD");

    private static final List<Trooper> ROSTER = List.of(
            JONAS, EDISON, SUN_FONG, BRYAN, SRIRAM, ZUL, SAMAY, RAIDEN, RAYAAN, HAOYI, AHMAD);
    private static final List<Trooper> NIGHT = List.of(JONAS, EDISON, SUN_FONG);

    private final ScheduleSolver solver = new ScheduleSolver();
    private final ScheduleValidator validator = new ScheduleValidator();

    @ParameterizedTest(name = "the {0} sheet solves and breaks no rule")
    @CsvSource({
            "2026-09-07, 47", // Monday
            "2026-09-08, 47",
            "2026-09-09, 47",
            "2026-09-10, 47",
            "2026-09-11, 44", // Friday — silent hours, then a Saturday
            "2026-09-12, 48", // Saturday — AP and GG right through
            "2026-09-13, 51" // Sunday — hands back to the weekday shape at Mon 0600
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

    /**
     * Step 5 held a hard partition here. Step 6 relaxes it by exactly one thing:
     * a night man may pick up the first few day hours coming off the silent block,
     * because AP straight onto the 0600 PAC needs no break and costs almost no
     * span. Everything else about the split is unchanged — and on a full weekday
     * the crossover earns nothing, so the hard partition is what actually comes
     * back.
     */
    @Test
    @DisplayName("the split holds, give or take the crossover window")
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
    @DisplayName("on a full weekday the crossover earns nothing, so nobody crosses")
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

    /**
     * The case crossover exists for. Three men on the silent hours can only reach
     * five each while the four left on days sit near eight — and no choice of
     * group size fixes that, because both groups are already as small as they can
     * be. Letting the night group take a few early day hours does.
     */
    @Test
    @DisplayName("on a thin day the crossover brings both groups within an hour")
    void crossoverRescuesAThinDay() {
        List<Trooper> thin = List.of(JONAS, EDISON, SUN_FONG, BRYAN, SRIRAM, SAMAY, AHMAD);

        SolveResult result = solver.solve(
                SolveRequest.of(DutyDay.of(LocalDate.of(2026, 9, 10)), thin, NIGHT));

        assertTrue(result.isSolved(), () -> "did not solve: " + result.reason());
        assertTrue(validator.validate(result.schedule()).ok());

        int fewest = result.schedule().rows().stream().mapToInt(Schedule.Row::hours).min().orElseThrow();
        int most = result.schedule().rows().stream().mapToInt(Schedule.Row::hours).max().orElseThrow();
        assertTrue(most - fewest <= 1, "hours ran " + fewest + " to " + most);
    }

    @Test
    @DisplayName("the answer is scored, and a legal sheet is never returned in place of a better legal sheet")
    void picksTheBestCandidateItFound() {
        SolveResult friday = solver.solve(SolveRequest.of(
                DutyDay.of(LocalDate.of(2026, 9, 11)), ROSTER, NIGHT));

        assertTrue(friday.isSolved());
        assertTrue(validator.validate(friday.schedule()).ok());
        // A Friday sheet is the silent hours and then a Saturday, and the stay-outs
        // are gone for the whole weekend block — so 16 silent hours fall on three
        // men and 28 daylight hours on five. One hour of spread is the floor, and
        // the search is expected to reach it rather than settle above it.
        assertTrue(ScheduleScore.of(friday.schedule()).hourSpread() <= 1,
                ScheduleScore.of(friday.schedule()).toString());
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
                Absence.fullDays(ZUL, AbsenceKind.MC, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 12)),
                Absence.appointment(BRYAN, LocalDate.of(2026, 9, 11), LocalTime.of(9, 30), LocalTime.of(11, 30)),
                Absence.nightHalf(SRIRAM, AbsenceKind.OL, LocalDate.of(2026, 9, 10)));

        SolveResult result = solver.solve(SolveRequest.of(day, ROSTER, NIGHT).withAbsences(leave));

        assertTrue(result.isSolved(), () -> "did not solve: " + result.reason());
        assertTrue(validator.validate(result.schedule()).ok());
        assertEquals(0, result.schedule().hours(ZUL), "a man on MC does nothing");

        Schedule.Row bryan = result.schedule().row(BRYAN).orElseThrow();
        assertNull(bryan.at(13), "0900 — he is at his appointment");
        assertNull(bryan.at(14), "1000");
        assertNull(bryan.at(15), "1100");
        assertTrue(bryan.hours() > 0, "but he still works the rest of the sheet");

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
        Map<Trooper, Integer> history = Map.of(JONAS, 100, EDISON, 90, SUN_FONG, 80);

        SolveResult result = solver.solve(
                SolveRequest.of(day, ROSTER, NIGHT).withHistory(history));

        assertTrue(result.isSolved());
        assertEquals(6, result.schedule().hours(SUN_FONG), "lowest cumulative hours takes the extra");
        assertEquals(5, result.schedule().hours(JONAS));
        assertEquals(47, totalHours(result.schedule()));
    }

    @Test
    @DisplayName("a night group that cannot hold the silent hours is refused, and says so")
    void shortNightGroupIsRefused() {
        SolveResult result = solver.solve(SolveRequest.of(
                DutyDay.of(LocalDate.of(2026, 9, 10)), ROSTER, List.of(JONAS, EDISON)));

        assertFalse(result.isSolved());
        assertTrue(result.reason().contains("at least 3"), result.reason());
    }

    @Test
    @DisplayName("too few men is refused with the arithmetic, not a shrug")
    void thinRosterIsRefused() {
        SolveResult result = solver.solve(SolveRequest.of(
                DutyDay.of(LocalDate.of(2026, 9, 10)),
                List.of(JONAS, EDISON, SUN_FONG, BRYAN), NIGHT));

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
