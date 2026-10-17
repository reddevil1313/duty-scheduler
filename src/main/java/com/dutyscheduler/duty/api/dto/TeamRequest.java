package com.dutyscheduler.duty.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TeamRequest(
        @NotBlank(message = "A team needs a name.")
        @Size(max = 60, message = "Keep the team name to 60 characters.") String name) {
}
