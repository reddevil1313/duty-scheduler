package com.sagarsamay.duty.domain;

import java.time.LocalDateTime;

/**
 * One hour of a duty sheet.
 *
 * <p>
 * {@code index} is the column on the sheet, 0 through 23. {@code start} is the
 * absolute instant that column begins, which is what everything else is decided
 * from — a slot knows its own date, so nothing downstream has to remember that
 * column 12 is "the following morning".
 */
public record Slot(int index, LocalDateTime start) {

    public Slot {
        if (index < 0 || index >= DutyDay.SLOT_COUNT) {
            throw new IllegalArgumentException("Slot index out of range: " + index);
        }
    }

    public LocalDateTime end() {
        return start.plusHours(1);
    }

    /** The sheet's own label for this hour: 2000, 2100, ... 1900. */
    public String label() {
        return String.format("%02d00", start.getHour());
    }

    /** True when any part of this hour falls inside {@code [from, to)}. */
    public boolean overlaps(LocalDateTime from, LocalDateTime to) {
        return from.isBefore(end()) && to.isAfter(start);
    }
}
