package com.dutyscheduler.duty.api.dto;

import com.dutyscheduler.duty.persistence.TrooperEntity;

/**
 * A name on the roster, with the flags a client needs to lay out a board.
 *
 * @param id what edits and deletes address. Not the name: a rename would
 *           otherwise change the address of the thing being renamed.
 */
public record TrooperResponse(long id, String name, String rank, boolean stayOut,
        boolean permPac, boolean onNight, boolean defaultBus) {

    public static TrooperResponse from(TrooperEntity entity) {
        return new TrooperResponse(entity.getId(), entity.getName(), entity.getRank(),
                entity.isStayOut(), entity.isPermPac(), entity.isOnNight(), entity.isDefaultBus());
    }
}
