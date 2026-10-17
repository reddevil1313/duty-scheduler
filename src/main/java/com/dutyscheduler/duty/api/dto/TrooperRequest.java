package com.dutyscheduler.duty.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A Trooper to add or edit. The name is the short board name.
 *
 * @param onNight    pre-ticked for the night group on the board
 * @param defaultBus the board's starting BUS pick; null leaves it unchanged
 */
public record TrooperRequest(
        @NotBlank(message = "A name is required.")
        @Size(max = 40, message = "Keep the name to 40 characters.") String name,

        @Size(max = 20, message = "Keep the rank to 20 characters.") String rank,

        boolean stayOut,
        boolean onNight,

        Boolean defaultBus) {
}
