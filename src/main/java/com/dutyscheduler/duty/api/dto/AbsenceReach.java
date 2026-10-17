package com.dutyscheduler.duty.api.dto;

import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.DutyDay;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The resolved interval of an absence and the sheets it reaches. Sheets are
 * named by the evening they start, so leave on the 11th mostly lands on the
 * 10th's sheet. Computed here so the client doesn't need the rule.
 *
 * @param covers when the absence starts
 * @param until  when it ends; exclusive
 * @param sheets every duty date whose sheet it reaches, earliest first
 */
public record AbsenceReach(LocalDateTime covers, LocalDateTime until, List<LocalDate> sheets) {

    public static AbsenceReach of(Absence absence) {
        // Candidates: the day before it starts through its last date.
        List<LocalDate> sheets = new ArrayList<>();
        for (LocalDate d = absence.from().minusDays(1); !d.isAfter(absence.to()); d = d.plusDays(1)) {
            if (absence.touches(DutyDay.of(d))) {
                sheets.add(d);
            }
        }
        return new AbsenceReach(absence.startsAt(), absence.endsAt(), List.copyOf(sheets));
    }
}
