package com.dutyscheduler.duty.solver;

import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.DutyRules;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleScoreTest {

    private static final LocalDate THURSDAY = LocalDate.of(2026, 9, 10);
    private static final Trooper JONAS = Trooper.of("JONAS");
    private static final Trooper RAIDEN = Trooper.stayOut("RAIDEN");

    private static Schedule.Row row(Trooper trooper, String spec) {
        return Schedule.builder(DutyDay.of(THURSDAY)).row(trooper, spec).build().rows().get(0);
    }

    @ParameterizedTest(name = "{0} hours can be done in no less than {1}")
    @CsvSource({ "0, 0", "1, 1", "3, 3", "4, 5", "6, 7", "7, 9", "9, 11" })
    @DisplayName("the break rule sets a floor on how tight a span can be")
    void tightestSpanIsBoundedByTheBreakRule(int hours, int tightest) {
        assertEquals(tightest, DutyRules.tightestSpan(hours));
    }

    /**
     * The rule that makes an early-evening stint affordable. Without it, a man who
     * works 2000-2200 and comes back at 0800 looks like he has been on duty for
     * fourteen hours, and the search will not give anyone an evening hour again.
     */
    @Test
    @DisplayName("a gap of ten hours or more is rest, and does not count as span")
    void aLongGapIsRestNotSpan() {
        Schedule.Row split = row(JONAS, "AP AP . . . . . . . . . . PAC PAC PAC . . . . . . . . .");

        assertEquals(5, split.hours());
        assertEquals(5, split.span(), "two turnouts of 2h and 3h, not one of fourteen");
        assertEquals(0, split.excessSpan(), "and none of it is wasted");
    }

    @Test
    @DisplayName("a gap shorter than that is just waiting around, and counts")
    void aShortGapIsNotRest() {
        Schedule.Row waiting = row(JONAS, ". . AP AP . . . . PAC PAC . . . . . . . . . . . . . .");

        assertEquals(4, waiting.hours());
        assertEquals(8, waiting.span(), "2200 to 0500 inclusive");
        assertEquals(3, waiting.excessSpan(), "four hours of duty needed five, so three are wasted");
    }

    @Test
    @DisplayName("stay-outs are left out of the span score")
    void stayOutsAreExempt() {
        Schedule sprawling = Schedule.builder(DutyDay.of(THURSDAY))
                .row(RAIDEN, ". . . . . . . . . . . . PAC . . . . . . . PAC . . .")
                .build();

        // He is on camp 0800 to 1800 whatever he is doing, so spreading his two
        // hours across it costs him nothing and the score should not pretend it does.
        assertEquals(0, ScheduleScore.of(sprawling).totalExcess());
        assertTrue(sprawling.rows().get(0).excessSpan() > 0, "the row itself still reports the span");
    }

    @Test
    @DisplayName("hours come before span, and the worst man before the total")
    void theOrderingIsLexicographic() {
        ScheduleScore evenHoursLooseSpans = new ScheduleScore(1, 6, 20);
        ScheduleScore unevenHoursTightSpans = new ScheduleScore(3, 0, 0);
        ScheduleScore oneManRuined = new ScheduleScore(1, 9, 9);
        ScheduleScore spreadAround = new ScheduleScore(1, 3, 12);

        assertTrue(evenHoursLooseSpans.isBetterThan(unevenHoursTightSpans),
                "an hour of unfairness is worse than several hours of hanging about");
        assertTrue(spreadAround.isBetterThan(oneManRuined),
                "three men mildly inconvenienced beats one man's day wrecked, even at a higher total");
        assertTrue(new ScheduleScore(1, 3, 5).isBetterThan(new ScheduleScore(1, 3, 6)),
                "the total only breaks ties");
        assertTrue(new ScheduleScore(0, 0, 0).isBetterThan(null), "anything beats nothing");
    }
}
