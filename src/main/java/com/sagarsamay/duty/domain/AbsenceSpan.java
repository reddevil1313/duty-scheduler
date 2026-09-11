package com.sagarsamay.duty.domain;

/** How much of the clock an absence takes. */
public enum AbsenceSpan {

    /** Whole days, midnight on the first through 2000 on the last. */
    FULL,

    /** 0800 to 2000 on one day. */
    DAY_HALF,

    /** 2000 on one day through 0800 the next. */
    NIGHT_HALF,

    /** An arbitrary start and end time on one day. */
    WINDOW
}
