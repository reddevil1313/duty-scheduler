package com.dutyscheduler.duty.api.dto;

import com.dutyscheduler.duty.domain.AbsenceKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A roster tick: whole-sheet leave for one generate or check, never saved.
 *
 * @param kind MC, OL or AL (appointments are booked in the register)
 */
public record QuickLeave(
        @NotBlank(message = "Which Trooper is ticked out?") String trooper,
        @NotNull(message = "MC, OL or AL?") AbsenceKind kind) {
}
