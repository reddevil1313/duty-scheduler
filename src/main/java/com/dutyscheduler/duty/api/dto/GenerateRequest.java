package com.dutyscheduler.duty.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * Ask for a sheet.
 *
 * @param out Troopers ticked out on the roster for this sheet only; see
 *            {@link QuickLeave}. Optional.
 * @param bus     who takes the BUS run, by name; optional, and the solver
 *                chooses when it is left out. Refused with a 422 when they
 *                cannot stand it.
 * @param turnout everyone's hours, 5 to 7, with the rest of the sheet left to
 *                the MP team; optional, and the free split when left out.
 *                Refused with a 422 when it makes more hours than the sheet needs.
 */
public record GenerateRequest(
        @NotNull(message = "A duty date is required.") LocalDate date,

        @NotEmpty(message = "Name at least one Trooper for the night group.") List<String> nightGroup,

        List<@Valid QuickLeave> out,

        String bus,

        @Min(value = 5, message = "A fixed turnout is 5 to 7 hours.")
        @Max(value = 7, message = "A fixed turnout is 5 to 7 hours.") Integer turnout) {
}
