package com.dutyscheduler.duty.api.dto;

import com.dutyscheduler.duty.persistence.InviteEntity;

import java.time.Instant;

/**
 * An invite, for the admin who manages it.
 *
 * @param token the secret the link is built from. Present only in the
 *              response that creates the invite: it is not stored, so it
 *              cannot be shown again, and a lost link is revoked and remade.
 */
public record InviteResponse(long id, String role, String createdBy, Instant createdAt, Instant expiresAt,
        String token) {

    public static InviteResponse from(InviteEntity invite, String token) {
        return new InviteResponse(invite.getId(), invite.getRole(), invite.getCreatedBy(), invite.getCreatedAt(),
                invite.getExpiresAt(), token);
    }
}
