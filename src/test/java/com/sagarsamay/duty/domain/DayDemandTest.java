package com.sagarsamay.duty.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class DayDemandTest {

    /**
     * The headline number for a sheet.
     *
     *   weekday  16h silent (8 x AP+GG) + 28h PAC (14 x 2) + 3h BUS   = 47
     *   Friday   2000-2200 empty, then AP+GG for 22 hours (2 x 22)    = 44
     *   Saturday AP+GG for the full 24 hours (2 x 24)                 = 48
     *   Sunday   20h AP+GG to Mon 0600, then 28h PAC + 3h BUS         = 51
     */
    @ParameterizedTest(name = "the {0} sheet holds {1} post-hours")
    @CsvSource({
            "2026-09-07, 47",   // Monday
            "2026-09-08, 47",   // Tuesday
            "2026-09-09, 47",   // Wednesday
            "2026-09-10, 47",   // Thursday
            "2026-09-11, 44",   // Friday
            "2026-09-12, 48",   // Saturday
            "2026-09-13, 51"    // Sunday
    })
    @DisplayName("post-hours per sheet")
    void postHoursPerSheet(String date, int expected) {
        DayDemand demand = DayDemand.of(DutyDay.of(LocalDate.parse(date)));
        assertEquals(expected, demand.postHours());
    }

    @Test
    @DisplayName("a weekday sheet is empty for two hours, then two-manned, then three at the bus run")
    void weekdayHeadcountByHour() {
        DayDemand d = DayDemand.of(DutyDay.of(LocalDate.of(2026, 9, 10)));

        assertEquals(0, d.headcountAt(0), "2000");
        assertEquals(0, d.headcountAt(1), "2100");
        assertEquals(2, d.headcountAt(2), "2200, silent hours start");
        assertEquals(2, d.headcountAt(9), "0500, still silent");
        assertEquals(2, d.headcountAt(10), "0600, two PAC");
        assertEquals(3, d.headcountAt(11), "0700, PAC plus the bus run");
        assertEquals(3, d.headcountAt(13), "0900, last bus hour");
        assertEquals(2, d.headcountAt(14), "1000, bus run over");
        assertEquals(2, d.headcountAt(23), "1900");
    }

    @Test
    @DisplayName("the Friday sheet is silent hours and then a Saturday")
    void fridaySheetIsMostlyWeekend() {
        DayDemand d = DayDemand.of(DutyDay.of(LocalDate.of(2026, 9, 11)));

        assertTrue(d.touchesWeekend());
        assertFalse(d.hasDayPosts(), "no PAC or BUS anywhere on a Friday sheet");
        assertEquals(0, d.headcountAt(0), "Fri 2000, before the block opens");
        assertEquals(2, d.headcountAt(2), "Fri 2200, the block opens");
        assertEquals(2, d.headcountAt(23), "Sat 1900, still AP and GG");
    }

    @Test
    @DisplayName("the Sunday sheet hands back to the weekday shape at Monday 0600")
    void sundaySheetCrossesBackOnMondayMorning() {
        DayDemand d = DayDemand.of(DutyDay.of(LocalDate.of(2026, 9, 13)));

        assertTrue(d.touchesWeekend());
        assertTrue(d.hasDayPosts(), "Monday daytime is on this sheet");
        assertEquals(2, d.headcountAt(9), "Mon 0500, last weekend hour");
        assertEquals(2, d.headcountAt(10), "Mon 0600, two PAC");
        assertEquals(3, d.headcountAt(11), "Mon 0700, the bus run is back");
    }

    @Test
    @DisplayName("a weekday sheet touches no weekend hour")
    void weekdaySheetIsNotWeekend() {
        DayDemand d = DayDemand.of(DutyDay.of(LocalDate.of(2026, 9, 8)));

        assertFalse(d.touchesWeekend());
        assertTrue(d.hasDayPosts());
    }

    @Test
    @DisplayName("the silent hours cannot hold as many duty hours as they look like they should")
    void silentHoursAreEightNotSixteen() {
        DayDemand d = DayDemand.of(DutyDay.of(LocalDate.of(2026, 9, 10)));

        int silent = 0;
        for (int i = 2; i < 10; i++) {
            silent += d.headcountAt(i);
        }
        assertEquals(16, silent);
    }
}
