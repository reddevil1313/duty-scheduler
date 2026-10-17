package com.dutyscheduler.duty.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The account made by accepting an invite. Usernames are lower case. Passwords
 * are 10 characters to 72 bytes; bcrypt ignores anything longer.
 */
public record AcceptInviteRequest(
        @NotBlank(message = "Choose a username.")
        @Pattern(regexp = "[a-z0-9_.-]{3,32}",
                message = "A username is 3 to 32 lower-case letters, digits, dots, dashes or underscores.")
        String username,

        @NotBlank(message = "Give the name to show for you.")
        @Size(max = 60, message = "Keep the name to 60 characters.") String displayName,

        @NotBlank(message = "Choose a password.")
        @Size(min = 10, message = "A password is at least 10 characters.") String password) {
}
