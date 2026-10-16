package com.dutyscheduler.duty.api.dto;

import com.dutyscheduler.duty.persistence.TrooperEntity;

/** A name on the roster, with the flags a client needs to lay out a board. */
public record TrooperResponse(String name, String rank, boolean stayOut,
        boolean permPac, boolean onNight) {

    public static TrooperResponse from(TrooperEntity entity) {
        return new TrooperResponse(entity.getName(), entity.getRank(),
                entity.isStayOut(), entity.isPermPac(), entity.isOnNight());
    }
}
