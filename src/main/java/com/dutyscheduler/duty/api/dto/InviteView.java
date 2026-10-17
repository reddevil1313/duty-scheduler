package com.dutyscheduler.duty.api.dto;

import java.time.Instant;

/** What someone opening an invite link is shown before they accept: which team, as what. */
public record InviteView(String team, String role, Instant expiresAt) {
}
