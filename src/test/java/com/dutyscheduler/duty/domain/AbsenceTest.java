package com.dutyscheduler.duty.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

class AbsenceTest {

    private static final Trooper TROOPER_K = Trooper.of("TROOPER_K");

    @Test
    @DisplayName("full-day leave runs from midnight to 2000 on its last day")
    void fullLeaveEndsAtHandover() {
        Absence mc = Absence.fullDays(TROOPER_K, AbsenceKind.MC,
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 12));

        assertEquals(LocalDateTime.of(2026, 9, 10, 0, 0), mc.startsAt());
        assertEquals(LocalDateTime.of(2026, 9, 12, 20, 0), mc.endsAt());
    }

    /**
     * The case the whole interval model exists for. An MC "until the 12th" ends
     * at 2000 on the 12th, which is the exact moment the 12th's sheet begins — so
     * it takes the 11th's sheet whole and the 12th's not at all.
     */
    @ParameterizedTest(name = "the {0} sheet loses {1} of its 24 hours")
    @CsvSource({
            "2026-09-09, 20",   // the MC starts at midnight, four hours into this sheet
            "2026-09-10, 24",
            "2026-09-11, 24",
            "2026-09-12, 0"     // starts at 2000, exactly when the MC ends
    })
    @DisplayName("an MC to the 12th never touches the 12th's sheet")
    void mcToTheTwelfthSparesTheTwelfth(String sheet, int lost) {
        Absence mc = Absence.fullDays(TROOPER_K, AbsenceKind.MC,
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 12));
        DutyDay day = DutyDay.of(LocalDate.parse(sheet));

        long covered = day.slots().stream().filter(mc::covers).count();

        assertEquals(lost, covered);
    }

    @Test
    @DisplayName("a day half is 0800 to 2000 and lands on the previous evening's sheet")
    void dayHalfLandsOnYesterdaysSheet() {
        Absence off = Absence.dayHalf(TROOPER_K, AbsenceKind.OL, LocalDate.of(2026, 9, 11));

        assertEquals(LocalDateTime.of(2026, 9, 11, 8, 0), off.startsAt());
        assertEquals(LocalDateTime.of(2026, 9, 11, 20, 0), off.endsAt());
        assertEquals(12, DutyDay.of(LocalDate.of(2026, 9, 10)).slots().stream().filter(off::covers).count());
        assertFalse(off.touches(DutyDay.of(LocalDate.of(2026, 9, 11))));
    }

    @Test
    @DisplayName("a night half is 2000 to 0800 and lands on that evening's own sheet")
    void nightHalfLandsOnTonightsSheet() {
        Absence off = Absence.nightHalf(TROOPER_K, AbsenceKind.OL, LocalDate.of(2026, 9, 10));

        assertEquals(LocalDateTime.of(2026, 9, 10, 20, 0), off.startsAt());
        assertEquals(LocalDateTime.of(2026, 9, 11, 8, 0), off.endsAt());
        assertEquals(12, DutyDay.of(LocalDate.of(2026, 9, 10)).slots().stream().filter(off::covers).count());
    }

    @Test
    @DisplayName("an appointment costs every hour it touches, part-hours included")
    void appointmentCostsWholeHours() {
        Absence ma = Absence.appointment(TROOPER_K, LocalDate.of(2026, 9, 11),
                LocalTime.of(9, 30), LocalTime.of(11, 30));
        DutyDay sheet = DutyDay.of(LocalDate.of(2026, 9, 10));

        String hours = sheet.slots().stream().filter(ma::covers)
                .map(Slot::label).reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);

        // 0930 to 1130 spills into three clock hours, and you cannot man half of one.
        assertEquals("0900 1000 1100", hours);
    }

    @Test
    @DisplayName("nonsense bookings are refused at construction")
    void badBookingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Absence.fullDays(
                TROOPER_K, AbsenceKind.AL, LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 10)));
        assertThrows(IllegalArgumentException.class, () -> Absence.appointment(
                TROOPER_K, LocalDate.of(2026, 9, 11), LocalTime.of(11, 0), LocalTime.of(9, 0)));
        assertThrows(IllegalArgumentException.class, () -> new Absence(
                TROOPER_K, AbsenceKind.MA, AbsenceSpan.WINDOW, LocalDate.of(2026, 9, 11), null, null, null));
    }
}
