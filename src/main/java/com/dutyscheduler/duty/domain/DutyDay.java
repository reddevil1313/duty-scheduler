package com.dutyscheduler.duty.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A duty sheet: twenty-four hourly slots running 2000 on {@code date} through
 * 1959 the following day.
 *
 * <p>
 * A sheet is named by the evening it starts, so the sheet for the 10th covers
 * the night of the 10th and the whole of the 11th's daytime. Slot 0 is 2000 on
 * the base date, slot 4 is midnight, slot 23 is 1900 the next day.
 */
public record DutyDay(LocalDate date) {

    public static final int SLOT_COUNT = 24;
    public static final LocalTime START = LocalTime.of(20, 0);

    public DutyDay {
        if (date == null) {
            throw new IllegalArgumentException("A duty day needs a date.");
        }
    }

    public static DutyDay of(LocalDate date) {
        return new DutyDay(date);
    }

    public LocalDateTime start() {
        return LocalDateTime.of(date, START);
    }

    /** Exclusive: 2000 the following day, which is where the next sheet begins. */
    public LocalDateTime end() {
        return start().plusHours(SLOT_COUNT);
    }

    public Slot slot(int index) {
        return new Slot(index, start().plusHours(index));
    }

    public List<Slot> slots() {
        List<Slot> out = new ArrayList<>(SLOT_COUNT);
        for (int i = 0; i < SLOT_COUNT; i++) {
            out.add(slot(i));
        }
        return List.copyOf(out);
    }

    /** Which slot covers an instant, or -1 if it falls on another sheet. */
    public int slotAt(LocalDateTime instant) {
        if (instant.isBefore(start()) || !instant.isBefore(end())) {
            return -1;
        }
        return (int) java.time.Duration.between(start(), instant).toHours();
    }
}
