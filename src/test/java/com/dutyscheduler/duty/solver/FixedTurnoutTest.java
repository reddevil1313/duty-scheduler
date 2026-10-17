package com.dutyscheduler.duty.solver;

import com.dutyscheduler.duty.domain.DayDemand;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Post;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;
import com.dutyscheduler.duty.rules.Availability;
import com.dutyscheduler.duty.rules.Rule;
import com.dutyscheduler.duty.rules.ScheduleValidator;
import com.dutyscheduler.duty.rules.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixed turnouts. The validator will report the MP team's hours as
 * under-manned; that must be the only thing it finds.
 */
class FixedTurnoutTest {

    private static final Trooper TROOPER_A = Trooper.of("TROOPER_A");
    private static final Trooper TROOPER_B = Trooper.of("TROOPER_B");
    private static final Trooper TROOPER_C = Trooper.of("TROOPER_C");
    private static final Trooper TROOPER_D = Trooper.of("TROOPER_D");
    private static final Trooper TROOPER_E = Trooper.of("TROOPER_E");
    private static final Trooper TROOPER_F = Trooper.of("TROOPER_F");
    private static final Trooper TROOPER_G = Trooper.of("TROOPER_G");
    private static final Trooper TROOPER_H = Trooper.stayOut("TROOPER_H");
    private static final Trooper TROOPER_I = Trooper.stayOut("TROOPER_I");

    /** The dev seed's nine: A to C on night, H and I stay out. */
    private static final List<Trooper> NINE = List.of(
            TROOPER_A, TROOPER_B, TROOPER_C, TROOPER_D, TROOPER_E, TROOPER_F, TROOPER_G, TROOPER_H, TROOPER_I);
    /** A thin day: six living in, nobody staying out. */
    private static final List<Trooper> SIX = List.of(TROOPER_A, TROOPER_B, TROOPER_C, TROOPER_D, TROOPER_E, TROOPER_F);
    private static final List<Trooper> NIGHT = List.of(TROOPER_A, TROOPER_B, TROOPER_C);

    private static final DutyDay THURSDAY = DutyDay.of(LocalDate.of(2026, 9, 10));

    private final ScheduleSolver solver = new ScheduleSolver();
    private final ScheduleValidator validator = new ScheduleValidator();

    private SolveResult solve(DutyDay day, List<Trooper> roster, int turnout) {
        return solver.solve(SolveRequest.of(day, roster, NIGHT).withTurnout(turnout));
    }

    private static int hours(Schedule sheet) {
        return sheet.rows().stream().mapToInt(Schedule.Row::hours).sum();
    }

    /** The guarantees every fixed-turnout sheet keeps, whatever the roster. */
    private void assertKeepsTheRules(Schedule sheet, int turnout) {
        DutyDay day = sheet.day();
        // Only UNDER_MANNED is allowed.
        ValidationResult check = validator.validate(sheet);
        assertTrue(check.violations().stream().allMatch(v -> v.rule() == Rule.UNDER_MANNED), check::describe);

        boolean[] silent = Availability.silentSlots(day);
        DayDemand demand = DayDemand.of(day);
        for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
            int need = demand.headcountAt(slot);
            final int s = slot;
            int on = (int) sheet.rows().stream().filter(r -> r.at(s) != null).count();
            if (silent[slot]) {
                assertEquals(need, on, "silent hour " + day.slot(slot).label() + " fully manned");
            } else if (need > 0) {
                assertTrue(on >= 1, day.slot(slot).label() + " keeps at least one Trooper on");
            }
        }
        // Everyone does N; night Troopers may do N + 1.
        for (Schedule.Row row : sheet.rows()) {
            if (row.hours() == 0) {
                continue;
            }
            String who = row.trooper().name() + " did " + row.hours() + "h at a " + turnout + "h turnout";
            if (NIGHT.contains(row.trooper())) {
                assertTrue(row.hours() == turnout || row.hours() == turnout + 1, who);
            } else {
                assertEquals(turnout, row.hours(), who);
            }
        }
    }

    @ParameterizedTest(name = "{0} roster, {1}h turnout, {2}: everyone does the turnout and the rules hold")
    @CsvSource({
            "nine, 5, 2026-09-07", "nine, 5, 2026-09-10", "nine, 5, 2026-09-11", "nine, 5, 2026-09-12", "nine, 5, 2026-09-13",
            "nine, 6, 2026-09-11", "nine, 6, 2026-09-12",
            "six, 5, 2026-09-10", "six, 5, 2026-09-11",
            "six, 6, 2026-09-08", "six, 6, 2026-09-12", "six, 6, 2026-09-13",
            "six, 7, 2026-09-09", "six, 7, 2026-09-12", "six, 7, 2026-09-13" })
    void keepsTheRules(String roster, int turnout, String date) {
        SolveResult result = solve(DutyDay.of(LocalDate.parse(date)), roster.equals("six") ? SIX : NINE, turnout);

        assertTrue(result.isSolved(), () -> "did not solve: " + result.reason());
        assertKeepsTheRules(result.schedule(), turnout);
    }

    @Test
    @DisplayName("5h each: 46 of 47 weekday hours, MP takes 1")
    void mpGetsTheRemainder() {
        Schedule sheet = solve(THURSDAY, NINE, 5).schedule();

        assertEquals(46, hours(sheet));
        assertEquals(1, DayDemand.of(THURSDAY).postHours() - hours(sheet), "one post-hour for the MP team");
    }

    @Test
    @DisplayName("night group goes to N + 1 only as needed")
    void nightGoesOneOver() {
        // 3 x 5 = 15 but silent hours need 16.
        Schedule sheet = solve(THURSDAY, NINE, 5).schedule();
        List<Integer> night = NIGHT.stream().map(sheet::hours).sorted().toList();
        assertEquals(List.of(5, 5, 6), night);
    }

    @Test
    @DisplayName("refuses a turnout with more hours than needed")
    void tooManyIsRefused() {
        List<Trooper> eleven = new java.util.ArrayList<>(NINE);
        eleven.add(Trooper.of("TROOPER_J"));
        eleven.add(Trooper.of("TROOPER_K"));
        SolveResult result = solve(THURSDAY, eleven, 5);

        assertFalse(result.isSolved());
        assertEquals("At 5h each the team makes 56 hours, more than the 47 this sheet needs."
                + " Use the free split, or a shorter turnout.", result.reason());
        assertEquals(0, result.nodes(), "refused before searching");
    }

    @Test
    @DisplayName("refuses too few hours for the silent block plus one an hour")
    void tooFewIsRefused() {
        SolveResult result = solve(DutyDay.of(LocalDate.of(2026, 9, 12)), SIX, 5);

        assertFalse(result.isSolved());
        assertTrue(result.reason().startsWith("At 5h each there are 31 hours for 32 that must have a Trooper on"),
                result.reason());
    }

    @Test
    @DisplayName("refuses when N + 1 still can't cover silent hours")
    void nightTooSmall() {
        SolveResult result = solver.solve(SolveRequest.of(THURSDAY, NINE, List.of(TROOPER_A, TROOPER_B)).withTurnout(5));

        assertFalse(result.isSolved());
        assertEquals("The silent hours need 16 Trooper-hours, and the night group at 5h, or 6h, each can give only 12."
                + " Add someone to the night group, or use the free split.", result.reason());
    }

    @Test
    @DisplayName("exact fit leaves nothing for MP")
    void exactFit() {
        // Stay-outs are away, so 8 x 6 = 48, exactly Saturday's demand.
        List<Trooper> eleven = new java.util.ArrayList<>(NINE);
        eleven.add(Trooper.of("TROOPER_J"));
        eleven.add(Trooper.stayOut("TROOPER_K"));
        Schedule sheet = solve(DutyDay.of(LocalDate.of(2026, 9, 12)), eleven, 6).schedule();

        assertEquals(48, hours(sheet));
        assertTrue(validator.validate(sheet).ok(), validator.validate(sheet)::describe);
    }

    @Test
    @DisplayName("fixed turnout with a BUS pick")
    void withBus() {
        SolveResult result = solver.solve(SolveRequest.of(THURSDAY, NINE, NIGHT).withTurnout(5).withBus(TROOPER_D));

        assertTrue(result.isSolved(), () -> "did not solve: " + result.reason());
        assertKeepsTheRules(result.schedule(), 5);
        Schedule.Row d = result.schedule().row(TROOPER_D).orElseThrow();
        assertEquals(List.of(Post.BUS, Post.BUS, Post.BUS), List.of(d.at(11), d.at(12), d.at(13)));
    }

    @Test
    @DisplayName("a turnout is 5 to 7 hours")
    void outOfRange() {
        assertThrows(IllegalArgumentException.class, () -> SolveRequest.of(THURSDAY, NINE, NIGHT).withTurnout(4));
        assertThrows(IllegalArgumentException.class, () -> SolveRequest.of(THURSDAY, NINE, NIGHT).withTurnout(8));
    }
}
