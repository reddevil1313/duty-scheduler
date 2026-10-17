package com.dutyscheduler.duty.domain;

import java.time.LocalDateTime;

/**
 * One hour of a duty sheet. {@code index} is the column, 0 to 23;
 * {@code start} is the actual date and time it begins.
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
