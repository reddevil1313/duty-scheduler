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
 * @param out        roster ticks for this sheet only; optional
 * @param bus        BUS Trooper by name; optional
 * @param busDefault true if {@code bus} is just the roster default, which the
 *                   solver may drop if it can't work
 * @param turnout    5 to 7, or null for the free split
 */
public record GenerateRequest(
        @NotNull(message = "A duty date is required.") LocalDate date,

        @NotEmpty(message = "Name at least one Trooper for the night group.") List<String> nightGroup,

        List<@Valid QuickLeave> out,

        String bus,

        Boolean busDefault,

        @Min(value = 5, message = "A fixed turnout is 5 to 7 hours.")
        @Max(value = 7, message = "A fixed turnout is 5 to 7 hours.") Integer turnout) {
}
