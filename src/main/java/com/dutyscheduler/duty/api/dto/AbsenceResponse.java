package com.dutyscheduler.duty.api.dto;

import com.dutyscheduler.duty.persistence.AbsenceEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * One entry in the leave register.
 *
 * @param covers the resolved interval's start, sent along so the client never
 *               has to re-derive the 2000 rule and get it subtly different
 * @param sheets the duty dates whose sheets it reaches; see {@link AbsenceReach}
 */
public record AbsenceResponse(Long id, String trooper, String kind, String span,
        LocalDate from, LocalDate to, LocalTime start, LocalTime end, String note,
        LocalDateTime covers, LocalDateTime until, List<LocalDate> sheets) {

    public static AbsenceResponse from(AbsenceEntity entity) {
        AbsenceReach reach = AbsenceReach.of(entity.toDomain());
        return new AbsenceResponse(
                entity.getId(),
                entity.getTrooper().getName(),
                entity.getKind().name(),
                entity.getSpan().name(),
                entity.getFromDate(),
                entity.getToDate(),
                entity.getStartTime(),
                entity.getEndTime(),
                entity.getNote(),
                reach.covers(),
                reach.until(),
                reach.sheets());
    }
}
