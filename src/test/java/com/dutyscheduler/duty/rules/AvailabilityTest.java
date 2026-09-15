package com.dutyscheduler.duty.rules;

import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.AbsenceKind;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.DutyRules;
import com.dutyscheduler.duty.domain.Trooper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AvailabilityTest {

    private static final LocalDate THURSDAY = LocalDate.of(2026, 9, 10);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 9, 12);

    private static final Trooper TROOPER_A = Trooper.of("TROOPER_A");
    private static final Trooper TROOPER_B = Trooper.stayOut("TROOPER_B");

    @ParameterizedTest(name = "a free run of {0} hours holds {1} hours of duty")
    @CsvSource({ "0, 0", "1, 1", "2, 2", "3, 3", "4, 3", "5, 4", "7, 6", "8, 6", "24, 18" })
    @DisplayName("three on, one off, so a run never holds as much as it looks like it should")
    void stintCapacityArithmetic(int free, int holds) {
        assertEquals(holds, DutyRules.stintCapacity(free));
    }

    @Test
    @DisplayName("a stay-out has ten hours of a weekday sheet, 0800 to 1800")
    void stayOutWeekdayWindow() {
        boolean[] mask = Availability.mask(TROOPER_B, DutyDay.of(THURSDAY), List.of());

        assertEquals(10, Availability.freeHours(mask));
        for (int i = 0; i < DutyDay.SLOT_COUNT; i++) {
            int hour = DutyDay.of(THURSDAY).slot(i).start().getHour();
            assertEquals(hour >= 8 && hour < 18, mask[i],
                    "slot " + i + " at " + DutyDay.of(THURSDAY).slot(i).label());
        }
    }

    @Test
    @DisplayName("a stay-out has none of a weekend sheet at all")
    void stayOutHasNoWeekend() {
        assertEquals(0, Availability.freeHours(
                Availability.mask(TROOPER_B, DutyDay.of(SATURDAY), List.of())));
        assertEquals(24, Availability.freeHours(
                Availability.mask(TROOPER_A, DutyDay.of(SATURDAY), List.of())),
                "a stay-in still has the whole sheet");
    }

    @Test
    @DisplayName("an absence takes only the hours it actually overlaps")
    void absenceTakesOnlyItsOwnHours() {
        Absence ma = Absence.appointment(TROOPER_A, LocalDate.of(2026, 9, 11),
                java.time.LocalTime.of(9, 30), java.time.LocalTime.of(11, 30));

        boolean[] mask = Availability.mask(TROOPER_A, DutyDay.of(THURSDAY), List.of(ma));

        assertEquals(21, Availability.freeHours(mask), "three hours gone, the rest of the sheet intact");
    }

    @Test
    @DisplayName("somebody else's leave is not your problem")
    void absencesAreFilteredByTrooper() {
        Absence hisLeave = Absence.fullDays(TROOPER_B, AbsenceKind.MC, THURSDAY, THURSDAY);

        assertEquals(24, Availability.freeHours(
                Availability.mask(TROOPER_A, DutyDay.of(THURSDAY), List.of(hisLeave))));
    }

    @Test
    @DisplayName("night is the clock, not the post — the same eight slots on any sheet")
    void nightIsDefinedByTheClock() {
        boolean[] weekday = Availability.silentSlots(DutyDay.of(THURSDAY));
        boolean[] weekend = Availability.silentSlots(DutyDay.of(SATURDAY));

        assertArrayEquals(weekday, weekend,
                "on a Saturday every post is AP or GG, so only the clock can say what night is");
        assertEquals(8, countTrue(weekday));
        assertFalse(weekday[0], "2000 is not a silent hour");
        assertTrue(weekday[2], "2200 is");
        assertTrue(weekday[9], "0500 is");
        assertFalse(weekday[10], "0600 is not");
    }

    @Test
    @DisplayName("the silent hours and the day hours partition the sheet")
    void silentAndDayHoursAddUp() {
        DutyDay day = DutyDay.of(THURSDAY);

        int silent = Availability.demandWithin(day, Availability.silentSlots(day));
        int daytime = Availability.demandWithin(day, Availability.daySlots(day));

        assertEquals(16, silent);
        assertEquals(31, daytime);
        assertEquals(47, silent + daytime);
    }

    /**
     * The result this whole step exists to produce. Nobody decided three; it is
     * what the stint rule does to the silent block, and it would move on its own
     * if either number changed.
     */
    @Test
    @DisplayName("three STs on night is arithmetic, not policy")
    void minimumNightGroupIsDerived() {
        DutyDay day = DutyDay.of(THURSDAY);
        boolean[] silent = Availability.silentSlots(day);

        assertEquals(16, Availability.demandWithin(day, silent), "sixteen ST-hours to place");
        assertEquals(6, Availability.capacity(silent), "and one man can supply six of them");
        assertEquals(3, Availability.minimumNightGroup(day), "so the night group cannot be smaller than three");

        // Same on a weekend sheet, because the silent block is the same eight hours.
        assertEquals(3, Availability.minimumNightGroup(DutyDay.of(SATURDAY)));
    }

    @Test
    @DisplayName("capacity within a window is the intersection, not the whole mask")
    void capacityWithinAWindow() {
        DutyDay day = DutyDay.of(THURSDAY);
        boolean[] stayOut = Availability.mask(TROOPER_B, day, List.of());

        assertEquals(0, Availability.capacityWithin(stayOut, Availability.silentSlots(day)),
                "a stay-out can contribute nothing to the silent hours");
        assertEquals(8, Availability.capacityWithin(stayOut, Availability.daySlots(day)),
                "ten free hours in one run hold eight hours of duty");
    }

    private static int countTrue(boolean[] mask) {
        int n = 0;
        for (boolean b : mask) {
            if (b) {
                n++;
            }
        }
        return n;
    }
}
