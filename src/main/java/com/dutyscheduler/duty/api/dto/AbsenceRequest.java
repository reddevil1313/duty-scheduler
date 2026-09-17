package com.dutyscheduler.duty.api.dto;

import com.dutyscheduler.duty.domain.AbsenceKind;
import com.dutyscheduler.duty.domain.AbsenceSpan;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Book leave.
 */
public record AbsenceRequest(
                @NotBlank(message = "Which Trooper?") String trooper,

                @NotNull(message = "MC, OL, AL or MA?") AbsenceKind kind,

                @NotNull(message = "Full days, a half day, or a time window?") AbsenceSpan span,

                @NotNull(message = "A start date is required.") LocalDate from,

                LocalDate to,
                LocalTime start,
                LocalTime end) {
}
