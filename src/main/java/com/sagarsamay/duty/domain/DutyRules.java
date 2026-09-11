package com.sagarsamay.duty.domain;

import java.util.Map;
import java.util.Set;

public final class DutyRules {
    public static final int MAX_STINT = 3;
    public static final int MIN_BREAK = 1;
    public static final int HOUR_CAP = 9;
    public static final int STAY_OUT_FROM = 8;
    public static final int STAY_OUT_TO = 18;
    public static final Map<Post, Set<Post>> FREE_AFTER = Map.of(
        Post.AP, Set.of(Post.PAC),
        Post.PAC, Set.of(Post.AP));
    
    /** Returns the maximum number of stints a trooper can take given the number of free hours available */
    public static int stintCapacity(int free) {
        return free - free / (MAX_STINT + MIN_BREAK);
    }
    private DutyRules() {}
}
