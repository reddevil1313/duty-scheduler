package com.dutyscheduler.duty.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DemandTest {

    private static LocalDateTime at(int day, int hour) {
        return LocalDateTime.of(2026, 9, day, hour, 0);
    }

    // 2026-09-10 is a Thursday, so the 11th is a Friday, the 12th a Saturday,
    // the 13th a Sunday and the 14th a Monday.
    @Test
    @DisplayName("calendar check")
    void calendarSanity() {
        assertEquals("THURSDAY", LocalDate.of(2026, 9, 10).getDayOfWeek().name());
        assertEquals("SUNDAY", LocalDate.of(2026, 9, 13).getDayOfWeek().name());
    }

    @Test
    @DisplayName("weekday 2000-2200 is empty")
    void weekdayEveningIsEmpty() {
        assertTrue(Demand.at(at(10, 20)).isEmpty());
        assertTrue(Demand.at(at(10, 21)).isEmpty());
    }

    @Test
    @DisplayName("silent hours are AP and GG")
    void silentHoursStandApAndGg() {
        Map<Post, Integer> ten = Demand.at(at(10, 22));

        assertEquals(Map.of(Post.AP, 1, Post.GG, 1), ten);
        assertEquals(Map.of(Post.AP, 1, Post.GG, 1), Demand.at(at(11, 5)));

        assertFalse(Demand.at(at(11, 6)).containsKey(Post.AP));
    }

    @Test
    @DisplayName("day: two PAC, BUS 0700-1000")
    void dayHoursStandTwoPacAndTheBusRun() {
        assertEquals(Map.of(Post.PAC, 2), Demand.at(at(11, 6)));
        assertEquals(Map.of(Post.PAC, 2, Post.BUS, 1), Demand.at(at(11, 7)));
        assertEquals(Map.of(Post.PAC, 2, Post.BUS, 1), Demand.at(at(11, 9)));
        // 1000 is PAC only.
        assertEquals(Map.of(Post.PAC, 2), Demand.at(at(11, 10)));
        assertEquals(Map.of(Post.PAC, 2), Demand.at(at(11, 19)));
    }

    @ParameterizedTest(name = "{0} {1}00 is weekend = {2}")
    @CsvSource({
            // Friday: the block opens at 2200, not before.
            "11, 21, false",
            "11, 22, true",
            "12, 3,  true",
            "12, 12, true",
            "13, 23, true",
            // Monday: the block closes at 0600.
            "14, 5,  true",
            "14, 6,  false",
            "14, 12, false"
    })
    @DisplayName("the weekend block runs Friday 2200 to Monday 0600")
    void weekendBlockBoundaries(int day, int hour, boolean weekend) {
        assertEquals(weekend, Demand.isWeekend(at(day, hour)));
    }

    @Test
    @DisplayName("weekend is AP and GG only")
    void weekendStandsApAndGgOnly() {
        Map<Post, Integer> saturdayNoon = Demand.at(at(12, 12));

        assertEquals(Map.of(Post.AP, 1, Post.GG, 1), saturdayNoon);
        assertFalse(saturdayNoon.containsKey(Post.PAC), "no PAC on the weekend block");
        assertFalse(Demand.at(at(12, 8)).containsKey(Post.BUS), "no bus run on the weekend block");
    }

    @Test
    @DisplayName("weekday shape from Monday 0600")
    void weekdayShapeResumesMondayMorning() {
        assertEquals(Map.of(Post.AP, 1, Post.GG, 1), Demand.at(at(14, 5)));
        assertEquals(Map.of(Post.PAC, 2), Demand.at(at(14, 6)));
        assertEquals(Map.of(Post.PAC, 2, Post.BUS, 1), Demand.at(at(14, 8)));
    }

    @Test
    @DisplayName("result is unmodifiable")
    void demandIsImmutable() {
        Map<Post, Integer> m = Demand.at(at(11, 8));
        assertThrows(UnsupportedOperationException.class, () -> m.put(Post.AP, 1));
    }
}
