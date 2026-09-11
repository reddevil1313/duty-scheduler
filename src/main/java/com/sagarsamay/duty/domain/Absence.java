package com.sagarsamay.duty.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * An absence is a period of time when a trooper is not available for duty. 
 * It has a kind, a span, and a start and end time.
 * 
 * @param trooper the trooper who is absent
 * @param kind the kind of absence (e.g. MC, OL, AL, MA)
 * @param span the span of the absence (e.g. FULL, DAY_HALF, NIGHT_HALF, WINDOW)
 * @param from the start date of the absence
 * @param to the end date of the absence (inclusive)
 * @param start the start time of the absence (for WINDOW span)
 * @param end the end time of the absence (for WINDOW span)
 */
public record Absence(Trooper trooper, AbsenceKind kind, AbsenceSpan span,
                      LocalDate from, LocalDate to, LocalTime start, LocalTime end) {

    public static final LocalTime MORNING = LocalTime.of(8, 0);
    public static final LocalTime HANDOVER = DutyDay.START;

    public Absence {
        if (trooper == null || kind == null || span == null || from == null) {
            throw new IllegalArgumentException("An absence needs a trooper, a kind, a span and a date.");
        }
        switch (span) {
            case FULL -> {
                if (to == null) {
                    to = from;
                }
                if (to.isBefore(from)) {
                    throw new IllegalArgumentException("Leave ends before it starts: " + from + " to " + to);
                }
                start = null;
                end = null;
            }
            case DAY_HALF, NIGHT_HALF -> {
                // A half day is one day by definition, whatever the caller passed.
                to = from;
                start = null;
                end = null;
            }
            case WINDOW -> {
                to = from;
                if (start == null || end == null) {
                    throw new IllegalArgumentException("A window needs a start and an end time.");
                }
                if (!end.isAfter(start)) {
                    throw new IllegalArgumentException("A window ends before it starts: " + start + " to " + end);
                }
            }
        }
    }

    public static Absence fullDays(Trooper trooper, AbsenceKind kind, LocalDate from, LocalDate to) {
        return new Absence(trooper, kind, AbsenceSpan.FULL, from, to, null, null);
    }

    public static Absence dayHalf(Trooper trooper, AbsenceKind kind, LocalDate on) {
        return new Absence(trooper, kind, AbsenceSpan.DAY_HALF, on, on, null, null);
    }

    public static Absence nightHalf(Trooper trooper, AbsenceKind kind, LocalDate on) {
        return new Absence(trooper, kind, AbsenceSpan.NIGHT_HALF, on, on, null, null);
    }

    public static Absence appointment(Trooper trooper, LocalDate on, LocalTime start, LocalTime end) {
        return new Absence(trooper, AbsenceKind.MA, AbsenceSpan.WINDOW, on, on, start, end);
    }

    public LocalDateTime startsAt() {
        return switch (span) {
            case FULL -> from.atStartOfDay();
            case DAY_HALF -> from.atTime(MORNING);
            case NIGHT_HALF -> from.atTime(HANDOVER);
            case WINDOW -> from.atTime(start);
        };
    }


    public LocalDateTime endsAt() {
        return switch (span) {
            case FULL -> to.atTime(HANDOVER);
            case DAY_HALF -> from.atTime(HANDOVER);
            case NIGHT_HALF -> from.plusDays(1).atTime(MORNING);
            case WINDOW -> from.atTime(end);
        };
    }

    /** True when this absence takes any part of that hour. */
    public boolean covers(Slot slot) {
        return slot.overlaps(startsAt(), endsAt());
    }

    /** True when this absence reaches the sheet at all. */
    public boolean touches(DutyDay day) {
        return startsAt().isBefore(day.end()) && endsAt().isAfter(day.start());
    }
}
