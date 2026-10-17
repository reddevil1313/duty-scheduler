package com.dutyscheduler.duty.api.dto;

/** A new team and its first-admin invite. */
public record TeamCreated(TeamResponse team, InviteResponse invite) {
}
