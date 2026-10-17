package com.dutyscheduler.duty.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record RoleRequest(
        @NotNull(message = "Say which role.")
        @Pattern(regexp = "ADMIN|USER", message = "A member is ADMIN or USER.") String role) {
}
