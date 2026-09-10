package com.sagarsamay.duty.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class DutyDayTest {

    private static final LocalDate THU = LocalDate.of(2026, 9, 10);

    @Test
    @DisplayName("a sheet runs 2000 on its own date to 2000 the next day")
    void spansTwentyFourHoursFromEightPm() {
        DutyDay day = DutyDay.of(THU);

        assertEquals(LocalDateTime.of(2026, 9, 10, 20, 0), day.start());
        assertEquals(LocalDateTime.of(2026, 9, 11, 20, 0), day.end());
        assertEquals(24, day.slots().size());
    }

    @Test
    @DisplayName("slot 0 is 2000 tonight, slot 4 is midnight, slot 23 is 1900 tomorrow")
    void slotsCarryTheirOwnDate() {
        DutyDay day = DutyDay.of(THU);

        assertEquals(LocalDateTime.of(2026, 9, 10, 20, 0), day.slot(0).start());
        assertEquals(LocalDateTime.of(2026, 9, 11, 0, 0), day.slot(4).start());
        assertEquals(LocalDateTime.of(2026, 9, 11, 19, 0), day.slot(23).start());
    }

    @Test
    @DisplayName("slot labels read the way the sheet does")
    void labelsMatchTheSheet() {
        DutyDay day = DutyDay.of(THU);

        assertEquals("2000", day.slot(0).label());
        assertEquals("0000", day.slot(4).label());
        assertEquals("0700", day.slot(11).label());
        assertEquals("1900", day.slot(23).label());
    }

    @Test
    @DisplayName("an instant maps back to the slot that covers it, or to nothing")
    void findsTheSlotCoveringAnInstant() {
        DutyDay day = DutyDay.of(THU);

        assertEquals(0, day.slotAt(LocalDateTime.of(2026, 9, 10, 20, 30)));
        assertEquals(12, day.slotAt(LocalDateTime.of(2026, 9, 11, 8, 0)));
        // 2000 the next evening belongs to the next sheet, not this one.
        assertEquals(-1, day.slotAt(LocalDateTime.of(2026, 9, 11, 20, 0)));
        assertEquals(-1, day.slotAt(LocalDateTime.of(2026, 9, 10, 19, 59)));
    }

    @Test
    @DisplayName("a slot overlaps a half-open interval only where it really does")
    void overlapIsHalfOpen() {
        Slot nine = DutyDay.of(THU).slot(13);   // 0900 on the 11th

        assertTrue(nine.overlaps(
                LocalDateTime.of(2026, 9, 11, 9, 30), LocalDateTime.of(2026, 9, 11, 11, 30)));
        // Touching at the boundary is not overlapping.
        assertFalse(nine.overlaps(
                LocalDateTime.of(2026, 9, 11, 10, 0), LocalDateTime.of(2026, 9, 11, 11, 0)));
        assertFalse(nine.overlaps(
                LocalDateTime.of(2026, 9, 11, 8, 0), LocalDateTime.of(2026, 9, 11, 9, 0)));
    }

    @Test
    @DisplayName("a slot index outside the sheet is rejected")
    void rejectsBadSlotIndex() {
        DutyDay day = DutyDay.of(THU);

        assertThrows(IllegalArgumentException.class, () -> day.slot(24));
        assertThrows(IllegalArgumentException.class, () -> day.slot(-1));
    }
}
