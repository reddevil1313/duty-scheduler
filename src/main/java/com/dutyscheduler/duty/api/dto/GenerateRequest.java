package com.dutyscheduler.duty.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * Ask for a sheet.
 *
 * @param out Troopers ticked out on the roster for this sheet only; see
 *            {@link QuickLeave}. Optional.
 */
public record GenerateRequest(
        @NotNull(message = "A duty date is required.") LocalDate date,

        @NotEmpty(message = "Name at least one Trooper for the night group.") List<String> nightGroup,

        List<@Valid QuickLeave> out) {
}
