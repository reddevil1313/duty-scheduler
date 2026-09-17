package com.dutyscheduler.duty.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * Ask for a sheet.
 */
public record GenerateRequest(
                @NotNull(message = "A duty date is required.") LocalDate date,

                @NotEmpty(message = "Name at least one ST for the night group.") List<String> nightGroup) {
}
