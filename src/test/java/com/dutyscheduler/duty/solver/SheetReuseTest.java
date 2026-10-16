package com.dutyscheduler.duty.solver;

import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.AbsenceKind;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Post;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;
import com.dutyscheduler.duty.rules.ScheduleValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A logged sheet laid onto a new day's Troopers. The old turnouts are kept
 * exactly; who stands each may change. Anything that cannot be laid, or comes
 * out breaking a rule, is solved instead — so each test checks both that reuse
 * happens when it should and that it steps aside when it should not.
 */
class SheetReuseTest {

    private static final Trooper TROOPER_A = Trooper.of("TROOPER_A");
    private static final Trooper TROOPER_B = Trooper.of("TROOPER_B");
    private static final Trooper TROOPER_C = Trooper.of("TROOPER_C");
    private static final Trooper TROOPER_D = Trooper.of("TROOPER_D");
    private static final Trooper TROOPER_E = Trooper.of("TROOPER_E");
    private static final Trooper TROOPER_F = Trooper.of("TROOPER_F");
    private static final Trooper TROOPER_G = Trooper.of("TROOPER_G");
    private static final Trooper TROOPER_H = Trooper.stayOut("TROOPER_H");
    private static final Trooper TROOPER_I = Trooper.stayOut("TROOPER_I");
    private static final Trooper TROOPER_J = Trooper.of("TROOPER_J");

    private static final List<Trooper> NINE = List.of(
            TROOPER_A, TROOPER_B, TROOPER_C, TROOPER_D, TROOPER_E, TROOPER_F, TROOPER_G, TROOPER_H, TROOPER_I);
    private static final List<Trooper> NIGHT = List.of(TROOPER_A, TROOPER_B, TROOPER_C);

    /** Thursday is logged; Monday has exactly the same posts and hours. */
    private static final DutyDay THURSDAY = DutyDay.of(LocalDate.of(2026, 9, 10));
    private static final DutyDay MONDAY = DutyDay.of(LocalDate.of(2026, 9, 14));

    private final ScheduleSolver solver = new ScheduleSolver();
    private final ScheduleValidator validator = new ScheduleValidator();

    private Schedule logged(SolveRequest request) {
        SolveResult result = solver.solve(request);
        assertTrue(result.isSolved(), () -> "could not make the logged sheet: " + result.reason());
        return result.schedule();
    }

    /** The turnouts on a sheet, whoever stands them: what reuse must keep. */
    private static List<List<Post>> turnouts(Schedule sheet) {
        List<List<Post>> out = new ArrayList<>(sheet.rows().stream()
                .filter(r -> r.hours() > 0).map(Schedule.Row::posts).toList());
        out.sort(Comparator.comparing(Object::toString));
        return out;
    }

    @Test
    @DisplayName("a Monday reuses a logged Thursday: the same turnouts, and a clean sheet")
    void reusesTheLoggedSheet() {
        Schedule thursday = logged(SolveRequest.of(THURSDAY, NINE, NIGHT));

        SolveResult monday = solver.solve(SolveRequest.of(MONDAY, NINE, NIGHT).withReusable(List.of(thursday)));

        assertEquals(THURSDAY.date(), monday.reusedFrom());
        assertEquals(MONDAY, monday.schedule().day());
        assertEquals(turnouts(thursday), turnouts(monday.schedule()));
        assertTrue(validator.validate(monday.schedule()).ok(), validator.validate(monday.schedule())::describe);
    }

    @Test
    @DisplayName("the same headcount with different people still reuses: someone new takes a row")
    void differentPeopleSameHeadcount() {
        Schedule thursday = logged(SolveRequest.of(THURSDAY, NINE, NIGHT));
        // TROOPER_D is on MC all Monday; TROOPER_J, not there on Thursday, is.
        List<Trooper> roster = new ArrayList<>(NINE);
        roster.add(TROOPER_J);
        Absence mc = Absence.fullDays(TROOPER_D, AbsenceKind.MC, MONDAY.date(), MONDAY.date().plusDays(1));

        SolveResult monday = solver.solve(SolveRequest.of(MONDAY, roster, NIGHT)
                .withAbsences(List.of(mc)).withReusable(List.of(thursday)));

        assertEquals(THURSDAY.date(), monday.reusedFrom());
        assertEquals(0, monday.schedule().hours(TROOPER_D), "on MC");
        assertTrue(monday.schedule().hours(TROOPER_J) > 0, "the newcomer has a row");
        assertEquals(turnouts(thursday), turnouts(monday.schedule()));
        assertTrue(validator.validate(monday.schedule()).ok());
    }

    @Test
    @DisplayName("a different headcount is not reused; the day is solved")
    void differentHeadcountIsSolved() {
        Schedule thursday = logged(SolveRequest.of(THURSDAY, NINE, NIGHT));
        List<Trooper> ten = new ArrayList<>(NINE);
        ten.add(TROOPER_J);

        SolveResult monday = solver.solve(SolveRequest.of(MONDAY, ten, NIGHT).withReusable(List.of(thursday)));

        assertNull(monday.reusedFrom());
        assertTrue(monday.isSolved());
    }

    @Test
    @DisplayName("rows that cannot be laid onto today's night group are not reused; the day is solved")
    void nightGroupMismatchIsSolved() {
        Schedule thursday = logged(SolveRequest.of(THURSDAY, NINE, NIGHT));
        // Four on night today: the fourth would need a night row, and there are three.
        List<Trooper> night = List.of(TROOPER_A, TROOPER_B, TROOPER_C, TROOPER_D);

        SolveResult monday = solver.solve(SolveRequest.of(MONDAY, NINE, night).withReusable(List.of(thursday)));

        assertNull(monday.reusedFrom());
        assertTrue(monday.isSolved(), () -> "did not solve: " + monday.reason());
    }

    @Test
    @DisplayName("a BUS pick takes the row with the BUS run")
    void busPickTakesTheBusRow() {
        Schedule thursday = logged(SolveRequest.of(THURSDAY, NINE, NIGHT).withBus(TROOPER_D));

        SolveResult monday = solver.solve(SolveRequest.of(MONDAY, NINE, NIGHT)
                .withBus(TROOPER_F).withReusable(List.of(thursday)));

        assertEquals(THURSDAY.date(), monday.reusedFrom());
        Schedule.Row f = monday.schedule().row(TROOPER_F).orElseThrow();
        assertEquals(List.of(Post.BUS, Post.BUS, Post.BUS), List.of(f.at(11), f.at(12), f.at(13)));
    }

    @Test
    @DisplayName("a BUS pick on a sheet whose BUS run was split is not reused: no one row holds it")
    void splitBusIsSolved() {
        // Left to itself the solver splits BUS across three Troopers on this roster.
        Schedule thursday = logged(SolveRequest.of(THURSDAY, NINE, NIGHT));
        assertTrue(thursday.rows().stream().filter(r -> r.posts().contains(Post.BUS)).count() > 1,
                "the premise: BUS split on the logged sheet");

        SolveResult monday = solver.solve(SolveRequest.of(MONDAY, NINE, NIGHT)
                .withBus(TROOPER_F).withReusable(List.of(thursday)));

        assertNull(monday.reusedFrom());
        assertTrue(monday.isSolved());
    }

    @Test
    @DisplayName("a logged sheet that broke a rule is never reused")
    void brokenSheetIsNotReused() {
        Schedule good = logged(SolveRequest.of(THURSDAY, NINE, NIGHT));
        // Take TROOPER_A off their first hour: under-manned, as a careless hand edit would be.
        Schedule.Row a = good.row(TROOPER_A).orElseThrow();
        int first = a.posts().indexOf(a.posts().stream().filter(p -> p != null).findFirst().orElseThrow());
        Schedule broken = good.with(TROOPER_A, first, null);
        assertFalse(validator.validate(broken).ok());

        SolveResult monday = solver.solve(SolveRequest.of(MONDAY, NINE, NIGHT).withReusable(List.of(broken)));

        assertNull(monday.reusedFrom());
        assertTrue(validator.validate(monday.schedule()).ok());
    }

    @Test
    @DisplayName("the longest row someone can stand goes to whoever has done fewest hours")
    void historyDecidesWhoGetsTheLongRow() {
        Schedule thursday = logged(SolveRequest.of(THURSDAY, NINE, NIGHT));
        // Everyone on 100 hours but TROOPER_G, on none.
        Map<Trooper, Integer> history = new java.util.HashMap<>();
        for (Trooper t : NINE) {
            history.put(t, t.equals(TROOPER_G) ? 0 : 100);
        }

        Schedule monday = solver.solve(SolveRequest.of(MONDAY, NINE, NIGHT)
                .withHistory(history).withReusable(List.of(thursday))).schedule();

        // TROOPER_G is on days, so any row without night hours is theirs to
        // take, a stay-out's included: the longest of those is theirs.
        int longestDayRow = thursday.rows().stream()
                .filter(r -> !NIGHT.contains(r.trooper()))
                .mapToInt(Schedule.Row::hours).max().orElseThrow();
        assertEquals(longestDayRow, monday.hours(TROOPER_G));
    }

    @Test
    @DisplayName("a fixed-turnout sheet is reused at the same turnout, MP gaps and all")
    void fixedTurnoutReused() {
        Schedule thursday = logged(SolveRequest.of(THURSDAY, NINE, NIGHT).withTurnout(5));

        SolveResult monday = solver.solve(SolveRequest.of(MONDAY, NINE, NIGHT)
                .withTurnout(5).withReusable(List.of(thursday)));

        assertEquals(THURSDAY.date(), monday.reusedFrom());
        assertEquals(turnouts(thursday), turnouts(monday.schedule()));
        assertEquals(46, monday.schedule().rows().stream().mapToInt(Schedule.Row::hours).sum());
    }

    @Test
    @DisplayName("of several candidates, the first that fits is used")
    void firstThatFitsWins() {
        Schedule thursday = logged(SolveRequest.of(THURSDAY, NINE, NIGHT));
        Schedule wednesday = logged(SolveRequest.of(DutyDay.of(LocalDate.of(2026, 9, 9)), NINE, NIGHT));

        SolveResult monday = solver.solve(SolveRequest.of(MONDAY, NINE, NIGHT)
                .withReusable(List.of(thursday, wednesday)));

        assertEquals(THURSDAY.date(), monday.reusedFrom(), "the latest is tried first");
    }
}
