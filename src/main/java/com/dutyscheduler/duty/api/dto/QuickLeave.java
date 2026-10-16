package com.dutyscheduler.duty.api.dto;

import com.dutyscheduler.duty.domain.AbsenceKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A tick on the roster: this Trooper is out of the sheet being worked on, for
 * this reason.
 *
 * <p>
 * Not a leave booking. It is sent with a generate or a check and forgotten as
 * soon as the answer comes back, so it shapes a potential sheet without writing
 * anything to the register. Treated as leave covering the whole sheet, so the
 * solver and the check handle it exactly as they would a booked MC.
 *
 * @param kind MC, OL or AL. An appointment is a few hours, not a sheet, and is
 *             booked in the register instead.
 */
public record QuickLeave(
        @NotBlank(message = "Which Trooper is ticked out?") String trooper,
        @NotNull(message = "MC, OL or AL?") AbsenceKind kind) {
}
