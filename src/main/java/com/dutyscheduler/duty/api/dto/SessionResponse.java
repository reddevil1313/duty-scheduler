package com.dutyscheduler.duty.api.dto;

/**
 * Who the caller is, plus the CSRF token. Returned to anonymous callers too,
 * since the login POST needs the token.
 *
 * @param authenticated false when anonymous; identity fields are then null
 * @param team          null when anonymous and for the owner
 */
public record SessionResponse(boolean authenticated,
        String username,
        String displayName,
        String role,
        String team,
        String csrfToken) {
}
