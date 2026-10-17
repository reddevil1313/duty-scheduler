package com.dutyscheduler.duty.api.dto;

import com.dutyscheduler.duty.persistence.AppUserEntity;

/**
 * An account in the caller's team.
 *
 * @param you whether this is the caller
 */
public record MemberResponse(long id, String username, String displayName, String role, boolean you) {

    public static MemberResponse from(AppUserEntity user, long callerId) {
        return new MemberResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(),
                user.getId() == callerId);
    }
}
