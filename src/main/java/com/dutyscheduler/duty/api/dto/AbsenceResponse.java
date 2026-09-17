package com.dutyscheduler.duty.api.dto;

import com.dutyscheduler.duty.persistence.AbsenceEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record AbsenceResponse(Long id, String trooper, String kind, String span,
        LocalDate from, LocalDate to, LocalTime start, LocalTime end,
        LocalDateTime covers, LocalDateTime until) {

    public static AbsenceResponse from(AbsenceEntity entity) {
        var domain = entity.toDomain();
        return new AbsenceResponse(
                entity.getId(),
                entity.getTrooper().getName(),
                entity.getKind().name(),
                entity.getSpan().name(),
                entity.getFromDate(),
                entity.getToDate(),
                entity.getStartTime(),
                entity.getEndTime(),
                // The resolved interval, sent along so the client never has to
                // re-derive the 2000 rule and get it subtly different.
                domain.startsAt(),
                domain.endsAt());
    }
}
