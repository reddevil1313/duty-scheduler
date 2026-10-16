package com.dutyscheduler.duty.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Add a Trooper to the roster.
 *
 * <p>
 * The name is the identifier everything else refers to: the night group in a
 * generate request, the Trooper on an absence, the row on a logged sheet. It is
 * the short name written on the board, not a full name — the roster stores no
 * more about a person than the scheduling needs.
 *
 * @param onNight whether the board should pre-tick this Trooper for the night
 *                group. A default, not a constraint: the night group is chosen
 *                per sheet.
 */
public record TrooperRequest(
        @NotBlank(message = "A name is required.")
        @Size(max = 40, message = "Keep the name to 40 characters.") String name,

        @Size(max = 20, message = "Keep the rank to 20 characters.") String rank,

        boolean stayOut,
        boolean onNight) {
}
