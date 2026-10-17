package com.dutyscheduler.duty.api.dto;

/** A new team and the invite for its first admin, which is the only way into it. */
public record TeamCreated(TeamResponse team, InviteResponse invite) {
}
