package com.dutyscheduler.duty.api.dto;

import java.time.Instant;

/** A team as the owner sees it. */
public record TeamResponse(long id, String name, Instant createdAt, long admins, long users) {
}
