package com.dutyscheduler.duty.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The account someone makes by accepting an invite.
 *
 * <p>
 * The username is lower case so that two accounts cannot differ only by case
 * and be mistaken for each other at sign-in. The password is 10 characters at
 * least, and 72 bytes at most because bcrypt ignores anything past that — a
 * longer one would quietly be checked only in part.
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
