package com.dutyscheduler.duty.api.dto;

import com.dutyscheduler.duty.domain.AbsenceKind;
import com.dutyscheduler.duty.domain.AbsenceSpan;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Book leave.
 *
 * @param to   last date of a full-day booking; ignored for any other span
 * @param note free text for the register, such as a clinic or a reference
 */
public record AbsenceRequest(
                @NotBlank(message = "Which Trooper?") String trooper,

                @NotNull(message = "MC, OL, AL or MA?") AbsenceKind kind,

                @NotNull(message = "Full days, a half day, or a time window?") AbsenceSpan span,

                @NotNull(message = "A start date is required.") LocalDate from,

                LocalDate to,
                LocalTime start,
                LocalTime end,

                @Size(max = 200, message = "Keep the note to 200 characters.") String note) {
}
