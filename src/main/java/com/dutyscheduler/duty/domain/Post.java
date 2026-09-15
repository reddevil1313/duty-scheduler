package com.dutyscheduler.duty.domain;

/**
 * A post that has to be manned. Which posts stand at a given hour is decided by
 * {@link Demand}; this enum only names them.
 *
 * <p>
 * {@link #concurrent()} is how many STs stand this post at the same time when
 * it is required at all. PAC is the only one that doubles up, and the two PAC
 * positions are interchangeable.
 */
public enum Post {

    AP(1),
    GG(1),
    PAC(2),
    BUS(1);

    private final int concurrent;

    Post(int concurrent) {
        this.concurrent = concurrent;
    }

    public int concurrent() {
        return concurrent;
    }
}
