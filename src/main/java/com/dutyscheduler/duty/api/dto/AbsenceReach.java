package com.dutyscheduler.duty.api.dto;

import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.DutyDay;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * What an absence actually takes: the resolved interval, and the duty sheets it
 * reaches.
 *
 * <p>
 * The sheets are the point. A sheet is named by the evening it starts, so leave
 * dated the 11th mostly lands on the 10th's sheet, and an MC "until the 12th"
 * stops at 2000 — exactly when the 12th's sheet begins, which it never touches.
 * Worked out here, from {@link Absence#touches}, so the client shows the rule
 * rather than re-implementing it.
 *
 * @param covers when the absence starts
 * @param until  when it ends; exclusive
 * @param sheets every duty date whose sheet it reaches, earliest first
 */
public record AbsenceReach(LocalDateTime covers, LocalDateTime until, List<LocalDate> sheets) {

    public static AbsenceReach of(Absence absence) {
        // A sheet starts at 2000 the evening before its morning, so the earliest
        // one an absence can reach is the day before it begins. Nothing ends
        // later than 2000 on its last date, so the last date's sheet is the
        // latest candidate — and touches() says whether it is reached.
        List<LocalDate> sheets = new ArrayList<>();
        for (LocalDate d = absence.from().minusDays(1); !d.isAfter(absence.to()); d = d.plusDays(1)) {
            if (absence.touches(DutyDay.of(d))) {
                sheets.add(d);
            }
        }
        return new AbsenceReach(absence.startsAt(), absence.endsAt(), List.copyOf(sheets));
    }
}
