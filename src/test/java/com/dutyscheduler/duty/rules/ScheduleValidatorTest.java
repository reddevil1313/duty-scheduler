package com.dutyscheduler.duty.rules;

import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Post;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the ScheduleValidator against a known-good Thursday sheet and various broken versions of it.
 * Each test is a single rule violation, so that the validator can be checked for reporting each rule independently.
 */
class ScheduleValidatorTest {

    private static final LocalDate THURSDAY = LocalDate.of(2026, 9, 10);

    static final Trooper TROOPER_A = Trooper.of("TROOPER_A");
    static final Trooper TROOPER_B = Trooper.of("TROOPER_B");
    static final Trooper TROOPER_C = Trooper.of("TROOPER_C");
    static final Trooper TROOPER_D = Trooper.of("TROOPER_D");
    static final Trooper TROOPER_E = Trooper.of("TROOPER_E");
    static final Trooper TROOPER_F = Trooper.of("TROOPER_F");
    static final Trooper TROOPER_G = Trooper.of("TROOPER_G");
    static final Trooper TROOPER_H = Trooper.stayOut("TROOPER_H");
    static final Trooper TROOPER_I = Trooper.stayOut("TROOPER_I");
    static final Trooper TROOPER_J = Trooper.stayOut("TROOPER_J");
    static final Trooper TROOPER_K = Trooper.of("TROOPER_K");

    private final ScheduleValidator validator = new ScheduleValidator();

    /**
     * A real, legal Thursday sheet: 47 post-hours over eleven STs, three on the
     * silent hours, the bus run on TROOPER_G, and the three stay-outs kept inside
     * 0800-1800. Every negative case below is this grid with one cell changed.
     */
    private static Schedule validThursday() {
        return Schedule.builder(DutyDay.of(THURSDAY))
                //                     2000 2100 2200 2300 0000 0100 0200 0300 0400 0500 0600 0700 0800 0900 1000 1100 1200 1300 1400 1500 1600 1700 1800 1900
                .row(TROOPER_A,    "  .    .    GG   GG   .    AP   AP   AP   .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .  ")
                .row(TROOPER_B,   "  .    .    AP   AP   AP   .    .    GG   GG   GG   .    .    .    .    .    .    .    .    .    .    .    .    .    .  ")
                .row(TROOPER_C, "  .    .    .    .    GG   GG   GG   .    AP   AP   .    .    .    .    .    .    .    .    .    .    .    .    .    .  ")
                .row(TROOPER_D,    "  .    .    .    .    .    .    .    .    .    .    PAC  PAC  PAC  .    .    .    .    .    .    .    .    .    .    PAC")
                .row(TROOPER_E,   "  .    .    .    .    .    .    .    .    .    .    PAC  PAC  PAC  .    .    .    .    .    .    .    .    .    .    PAC")
                .row(TROOPER_F,      "  .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    PAC  PAC  PAC  .  ")
                .row(TROOPER_G,    "  .    .    .    .    .    .    .    .    .    .    .    BUS  BUS  BUS  .    PAC  .    .    .    .    .    .    .    .  ")
                .row(TROOPER_H,   "  .    .    .    .    .    .    .    .    .    .    .    .    .    PAC  PAC  PAC  .    .    .    PAC  .    .    .    .  ")
                .row(TROOPER_I,   "  .    .    .    .    .    .    .    .    .    .    .    .    .    PAC  PAC  .    PAC  PAC  .    .    .    .    .    .  ")
                .row(TROOPER_J,    "  .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    PAC  PAC  PAC  .    .    PAC  .    .  ")
                .row(TROOPER_K,    "  .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    PAC  PAC  PAC  .    PAC  .  ")
                .build();
    }

    @Test
    @DisplayName("a real Thursday sheet comes back clean")
    void knownGoodScheduleHasNoViolations() {
        ValidationResult result = validator.validate(validThursday());
        assertTrue(result.ok(), () -> "expected a clean sheet but got:\n" + result.describe());
    }

    @Test
    @DisplayName("the fixture really is the 47-hour day, spread over eleven men")
    void fixtureMatchesTheDaysDemand() {
        Schedule s = validThursday();
        int total = s.rows().stream().mapToInt(Schedule.Row::hours).sum();

        assertEquals(47, total, "the sheet must absorb exactly the day's post-hours");
        assertEquals(11, s.rows().size());
        assertEquals(5, s.hours(TROOPER_A));
        assertEquals(4, s.hours(TROOPER_G));
    }

    @Nested
    @DisplayName("coverage")
    class Coverage {

        @Test
        @DisplayName("taking a man off a manned post reports the hour as under-manned")
        void missingManIsCaught() {
            Schedule broken = validThursday().with(TROOPER_A, 5, null);   // 0100, was AP

            ValidationResult result = validator.validate(broken);

            assertFalse(result.ok());
            assertTrue(result.has(Rule.UNDER_MANNED), result.describe());
            assertEquals(1, result.of(Rule.UNDER_MANNED).size(), "one hour short, not more");
            // slot() is an Integer, so unbox it and there is no doubt which
            // assertEquals overload is being called.
            assertEquals(5, (int) result.of(Rule.UNDER_MANNED).get(0).slot());
        }

        @Test
        @DisplayName("a post that does not stand at that hour is over-manned")
        void postStandingWhenItShouldNotIsCaught() {
            // 0200 is a silent hour: AP and GG only, no PAC anywhere near it.
            Schedule broken = validThursday().with(TROOPER_F, 6, Post.PAC);

            ValidationResult result = validator.validate(broken);

            assertTrue(result.has(Rule.OVER_MANNED), result.describe());
            assertTrue(result.of(Rule.OVER_MANNED).get(0).message().contains("does not stand"));
        }

        @Test
        @DisplayName("a third man on the two PAC positions is over-manned")
        void doublingUpOnAPostIsCaught() {
            Schedule broken = validThursday().with(TROOPER_F, 10, Post.PAC);   // 0600 already has two

            ValidationResult result = validator.validate(broken);

            assertTrue(result.has(Rule.OVER_MANNED), result.describe());
            assertTrue(result.of(Rule.OVER_MANNED).get(0).message().contains("needs 2, has 3"));
        }

        @Test
        @DisplayName("an empty sheet is short every single post-hour")
        void emptySheetIsShortEverything() {
            Schedule empty = Schedule.builder(DutyDay.of(THURSDAY))
                    .empty(TROOPER_A)
                    .build();

            ValidationResult result = validator.validate(empty);

            assertFalse(result.ok());
            // One violation per post per hour: 8 silent hours x AP and GG = 16,
            // 14 day hours of PAC = 14, plus the 3 bus hours. Nothing is manned,
            // so nothing can be over-manned either.
            assertEquals(16 + 14 + 3, result.of(Rule.UNDER_MANNED).size(), result.describe());
            assertFalse(result.has(Rule.OVER_MANNED));
        }
    }

    @Nested
    @DisplayName("stints, breaks and transitions")
    class Stints {

        @Test
        @DisplayName("four consecutive hours is one hour too many")
        void fourInARowIsCaught() {
            // Jonas has AP at 0100-0300. Give him 0400 as well and the run is four.
            Schedule broken = validThursday().with(TROOPER_A, 8, Post.AP);

            ValidationResult result = validator.validate(broken);

            assertTrue(result.has(Rule.STINT_TOO_LONG), result.describe());
            assertTrue(result.of(Rule.STINT_TOO_LONG).get(0).message().contains("4 hours"));
        }

        @Test
        @DisplayName("three consecutive hours is fine")
        void threeInARowIsFine() {
            ValidationResult result = validator.validate(validThursday());

            assertFalse(result.has(Rule.STINT_TOO_LONG));
        }

        @Test
        @DisplayName("AP straight onto PAC needs no break")
        void apToPacIsLegal() {
            // A clean two-man sheet: this is about the transition rule alone, so
            // coverage violations are expected and ignored.
            Schedule s = Schedule.builder(DutyDay.of(THURSDAY))
                    .row(TROOPER_A, ". . . . . . . . . AP PAC . . . . . . . . . . . . .")
                    .build();

            ValidationResult result = validator.validate(s);

            assertFalse(result.has(Rule.ILLEGAL_TRANSITION),
                    "0500 AP into 0600 PAC is explicitly allowed:\n" + result.describe());
        }

        @ParameterizedTest(name = "{0} straight onto {1} needs a break")
        @CsvSource({ "GG, PAC", "PAC, GG", "AP, GG", "GG, AP", "PAC, BUS", "BUS, PAC" })
        @DisplayName("every other post change needs a break")
        void otherTransitionsNeedABreak(String first, String second) {
            Schedule s = Schedule.builder(DutyDay.of(THURSDAY))
                    .row(TROOPER_A, ". . . . . . . . . " + first + " " + second + " . . . . . . . . . . . . .")
                    .build();

            ValidationResult result = validator.validate(s);

            assertTrue(result.has(Rule.ILLEGAL_TRANSITION),
                    first + " to " + second + " should need a break:\n" + result.describe());
        }

        @Test
        @DisplayName("a break resets the stint, so 3 on, 1 off, 3 on is legal")
        void breakResetsTheRun() {
            Schedule s = Schedule.builder(DutyDay.of(THURSDAY))
                    .row(TROOPER_A, ". . GG GG GG . AP AP AP . . . . . . . . . . . . . . .")
                    .build();

            ValidationResult result = validator.validate(s);

            assertFalse(result.has(Rule.STINT_TOO_LONG));
            assertFalse(result.has(Rule.ILLEGAL_TRANSITION), "the GG to AP change happens across a break");
        }
    }

    @Nested
    @DisplayName("stay-outs")
    class StayOuts {

        @Test
        @DisplayName("a stay-out posted before 0800 is caught")
        void beforeTheWindowIsCaught() {
            Schedule broken = validThursday().with(TROOPER_H, 11, Post.PAC);   // 0700

            ValidationResult result = validator.validate(broken);

            assertTrue(result.has(Rule.STAY_OUT_WINDOW), result.describe());
            assertEquals("TROOPER_H", result.of(Rule.STAY_OUT_WINDOW).get(0).who());
        }

        @Test
        @DisplayName("a stay-out posted at 1800 is caught — the window closes at 1800")
        void theWindowIsHalfOpenAtSixPm() {
            Schedule broken = validThursday().with(TROOPER_H, 22, Post.PAC);   // 1800

            ValidationResult result = validator.validate(broken);

            assertTrue(result.has(Rule.STAY_OUT_WINDOW), result.describe());
        }

        @Test
        @DisplayName("1700 is inside the window")
        void theWindowIncludesFivePm() {
            // TROOPER_J already works 1700 on the good sheet.
            assertFalse(validator.validate(validThursday()).has(Rule.STAY_OUT_WINDOW));
        }

        @Test
        @DisplayName("a stay-out anywhere on the weekend block is caught, even at noon")
        void weekendBlockIsOutEntirely() {
            // The Saturday sheet: every hour of it is inside the block, so a time
            // that would be perfectly legal midweek is not legal here.
            Schedule saturday = Schedule.builder(DutyDay.of(LocalDate.of(2026, 9, 12)))
                    .row(TROOPER_H, ". . . . . . . . . . . . . . . . AP . . . . . . .")   // 1200 Sunday
                    .build();

            ValidationResult result = validator.validate(saturday);

            assertTrue(result.has(Rule.STAY_OUT_WINDOW), result.describe());
            assertTrue(result.of(Rule.STAY_OUT_WINDOW).get(0).message().contains("weekend block"));
        }

        @Test
        @DisplayName("the same hours on a man who is not a stay-out are fine")
        void onlyAppliesToStayOuts() {
            Schedule s = Schedule.builder(DutyDay.of(THURSDAY))
                    .row(TROOPER_A, ". . AP . . . . . . . . . . . . . . . . . . . . .")   // 2200
                    .build();

            ValidationResult result = validator.validate(s);

            assertFalse(result.has(Rule.STAY_OUT_WINDOW));
        }
    }

    @Nested
    @DisplayName("the hour cap")
    class HourCap {

        @Test
        @DisplayName("nine hours is allowed")
        void nineIsFine() {
            Schedule s = Schedule.builder(DutyDay.of(THURSDAY))
                    .row(TROOPER_A, "AP AP AP . AP AP AP . AP AP AP . . . . . . . . . . . . .")
                    .build();

            assertEquals(9, s.hours(TROOPER_A));
            assertFalse(validator.validate(s).has(Rule.HOUR_CAP_EXCEEDED));
        }

        @Test
        @DisplayName("ten hours is not")
        void tenIsNot() {
            Schedule s = Schedule.builder(DutyDay.of(THURSDAY))
                    .row(TROOPER_A, "AP AP AP . AP AP AP . AP AP AP . AP . . . . . . . . . . .")
                    .build();

            ValidationResult result = validator.validate(s);

            assertEquals(10, s.hours(TROOPER_A));
            assertTrue(result.has(Rule.HOUR_CAP_EXCEEDED), result.describe());
            assertNull(result.of(Rule.HOUR_CAP_EXCEEDED).get(0).slot(), "the cap is about the sheet, not an hour");
        }
    }

    @Test
    @DisplayName("one broken rule does not drag the others in with it")
    void violationsAreReportedIndependently() {
        Schedule broken = validThursday().with(TROOPER_A, 8, Post.AP);   // four in a row

        ValidationResult result = validator.validate(broken);

        assertTrue(result.has(Rule.STINT_TOO_LONG));
        assertTrue(result.has(Rule.OVER_MANNED), "0400 now has a third man on AP");
        assertFalse(result.has(Rule.UNDER_MANNED));
        assertFalse(result.has(Rule.STAY_OUT_WINDOW));
        assertFalse(result.has(Rule.HOUR_CAP_EXCEEDED));
    }

    @Test
    @DisplayName("a weekend sheet is judged by its own demand, not a weekday's")
    void weekendSheetUsesWeekendDemand() {
        // Saturday: AP and GG right through, no PAC and no bus run anywhere.
        Schedule saturdayPac = Schedule.builder(DutyDay.of(LocalDate.of(2026, 9, 12)))
                .row(TROOPER_A, ". . . . . . . . . . . . PAC . . . . . . . . . . .")
                .build();

        ValidationResult result = validator.validate(saturdayPac);

        assertTrue(result.has(Rule.OVER_MANNED), "PAC does not stand on a Saturday:\n" + result.describe());
    }
}
