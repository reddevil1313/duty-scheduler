package com.dutyscheduler.duty.api.dto;

import com.dutyscheduler.duty.persistence.InviteEntity;

import java.time.Instant;

/**
 * An invite, for the admin who manages it.
 *
 * @param token only set when the invite is created; it isn't stored
 */
public record InviteResponse(long id, String role, String createdBy, Instant createdAt, Instant expiresAt,
        String token) {

    public static InviteResponse from(InviteEntity invite, String token) {
        return new InviteResponse(invite.getId(), invite.getRole(), invite.getCreatedBy(), invite.getCreatedAt(),
                invite.getExpiresAt(), token);
    }
}
