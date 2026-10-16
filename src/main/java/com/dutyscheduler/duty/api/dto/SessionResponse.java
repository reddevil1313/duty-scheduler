package com.dutyscheduler.duty.api.dto;

/**
 * Who the caller is, and the token their writes must carry.
 *
 * <p>
 * The two answers travel together on purpose. A client needs a CSRF token
 * before it can POST to the login endpoint — the login POST is itself protected
 * — so the endpoint that hands the token out cannot be one that requires being
 * signed in. Making this a 200 with {@code authenticated: false} rather than a
 * 401 is what lets one call serve both the anonymous and the signed-in case.
 *
 * @param authenticated false for an anonymous caller; the rest of the identity
 *                      fields are then null
 * @param csrfToken     always present, and always current for this request
 */
public record SessionResponse(boolean authenticated,
        String username,
        String displayName,
        String role,
        String csrfToken) {
}
