package com.dutyscheduler.duty.domain;

import java.util.Map;
import java.util.Set;

public final class DutyRules {
    private DutyRules() {}

    public static final int MAX_STINT = 3;
    public static final int MIN_BREAK = 1;
    public static final int HOUR_CAP = 9;
    public static final int STAY_OUT_FROM = 8;
    public static final int STAY_OUT_TO = 18;
    public static final int REST_GAP = 10;
    public static final int CROSS_WINDOW = 4;
    public static final Map<Post, Set<Post>> FREE_AFTER = Map.of(
        Post.AP, Set.of(Post.PAC),
        Post.PAC, Set.of(Post.AP));
    
    /** Returns the maximum number of stints a trooper can take given the number of free hours available */
    public static int stintCapacity(int free) {
        return free - free / (MAX_STINT + MIN_BREAK);
    }

    /**
     * The tightest span {@code hours} of duty could possibly occupy: three-hour
     * stints with a single hour between them.
     */
    public static int tightestSpan(int hours) {
        if (hours <= 0) {
            return 0;
        }
        return hours + (hours + MAX_STINT - 1) / MAX_STINT - 1;
    }
}
