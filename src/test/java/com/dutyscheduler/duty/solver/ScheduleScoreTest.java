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
    private static final Trooper TROOPER_E = Trooper.of("TROOPER_E");
    private static final Trooper TROOPER_F = Trooper.stayOut("TROOPER_F");

    private static Schedule.Row row(Trooper trooper, String spec) {
        return Schedule.builder(DutyDay.of(THURSDAY)).row(trooper, spec).build().rows().get(0);
    }

    @ParameterizedTest(name = "{0} hours can be done in no less than {1}")
    @CsvSource({ "0, 0", "1, 1", "3, 3", "4, 5", "6, 7", "7, 9", "9, 11" })
    @DisplayName("minimum span")
    void tightestSpanIsBoundedByTheBreakRule(int hours, int tightest) {
        assertEquals(tightest, DutyRules.tightestSpan(hours));
    }

    /** Otherwise 2000-2200 plus 0800 would look like 14 hours on duty. */
    @Test
    @DisplayName("a gap of ten or more is rest")
    void aLongGapIsRestNotSpan() {
        Schedule.Row split = row(TROOPER_E, "AP AP . . . . . . . . . . PAC PAC PAC . . . . . . . . .");

        assertEquals(5, split.hours());
        assertEquals(5, split.span(), "two turnouts of 2h and 3h, not one of fourteen");
        assertEquals(0, split.excessSpan(), "and none of it is wasted");
    }

    @Test
    @DisplayName("shorter gaps count as span")
    void aShortGapIsNotRest() {
        Schedule.Row waiting = row(TROOPER_E, ". . AP AP . . . . PAC PAC . . . . . . . . . . . . . .");

        assertEquals(4, waiting.hours());
        assertEquals(8, waiting.span(), "2200 to 0500 inclusive");
        assertEquals(3, waiting.excessSpan(), "four hours of duty needed five, so three are wasted");
    }

    @Test
    @DisplayName("stay-outs are excluded from span")
    void stayOutsAreExempt() {
        Schedule sprawling = Schedule.builder(DutyDay.of(THURSDAY))
                .row(TROOPER_F, ". . . . . . . . . . . . PAC . . . . . . . PAC . . .")
                .build();

        // They're in 0800-1800 anyway.
        assertEquals(0, ScheduleScore.of(sprawling).totalExcess());
        assertTrue(sprawling.rows().get(0).excessSpan() > 0, "the row itself still reports the span");
    }

    @Test
    @DisplayName("hours before span, worst before total")
    void theOrderingIsLexicographic() {
        ScheduleScore evenHoursLooseSpans = new ScheduleScore(1, 6, 20, 0);
        ScheduleScore unevenHoursTightSpans = new ScheduleScore(3, 0, 0, 0);
        ScheduleScore oneManRuined = new ScheduleScore(1, 9, 9, 0);
        ScheduleScore spreadAround = new ScheduleScore(1, 3, 12, 0);

        assertTrue(evenHoursLooseSpans.isBetterThan(unevenHoursTightSpans),
                "an hour of unfairness is worse than several hours of hanging about");
        assertTrue(spreadAround.isBetterThan(oneManRuined),
                "three Troopers mildly inconvenienced beats one Trooper's day wrecked, even at a higher total");
        assertTrue(new ScheduleScore(1, 3, 5, 0).isBetterThan(new ScheduleScore(1, 3, 6, 0)),
                "the total only breaks ties");
        assertTrue(new ScheduleScore(0, 0, 0, 0).isBetterThan(null), "anything beats nothing");
    }

    @Test
    @DisplayName("walks only break ties")
    void postChangesComeLast() {
        assertTrue(new ScheduleScore(1, 3, 5, 9).isBetterThan(new ScheduleScore(1, 3, 6, 0)),
                "an hour less wasted beats any number of walks saved");
        assertTrue(new ScheduleScore(0, 9, 30, 20).isBetterThan(new ScheduleScore(1, 0, 0, 0)),
                "an even share of hours beats a sheet with no walks at all");
        assertTrue(new ScheduleScore(1, 3, 5, 2).isBetterThan(new ScheduleScore(1, 3, 5, 3)),
                "with everything else level, fewer walks wins");
    }

    @Test
    @DisplayName("walks count for stay-outs too")
    void postChangesAreSummed() {
        Schedule sheet = Schedule.builder(DutyDay.of(THURSDAY))
                .row(TROOPER_E, ". . GG GG GG . AP . GG GG GG . . . . . . . . . . . . .")
                .row(TROOPER_F, ". . . . . . . . . . . . GG . AP . GG . . . . . . .")   // 0800 GG, 1000 AP, 1200 GG
                .build();

        assertEquals(4, ScheduleScore.of(sheet).postChanges(),
                "two each: a stay-out is spared the span score, not the walking");
    }
}
