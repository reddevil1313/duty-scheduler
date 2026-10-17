package com.dutyscheduler.duty.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** An invite to make: the role the person joins in. */
public record InviteRequest(
        @NotNull(message = "Say which role the invite is for.")
        @Pattern(regexp = "ADMIN|USER", message = "An invite is for ADMIN or USER.") String role) {
}
