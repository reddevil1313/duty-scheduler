package com.dutyscheduler.duty.api.dto;

import java.time.Instant;

/** Shown before accepting an invite. */
public record InviteView(String team, String role, Instant expiresAt) {
}
