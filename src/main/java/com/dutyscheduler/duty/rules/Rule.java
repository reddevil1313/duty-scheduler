package com.dutyscheduler.duty.rules;

/**
 * Represents the taxonomy of hard rules that every schedule should follow.
 */
public enum Rule {
    /** An hour is short of the STs its posts require. */
    UNDER_MANNED,

    /** An hour has more STs on a post than it needs, or a post that does not stand at all. */
    OVER_MANNED,

    /** More than a fixed number of consecutive hours of duty without a break. */
    STINT_TOO_LONG,

    /** A post change with no break where the two posts do not allow it. */
    ILLEGAL_TRANSITION,

    /** A stay-out posted outside the duty window, or anywhere in the weekend block. */
    STAY_OUT_WINDOW,

    /** More than a fixed number of hours of duty in one sheet. */
    HOUR_CAP_EXCEEDED
}
