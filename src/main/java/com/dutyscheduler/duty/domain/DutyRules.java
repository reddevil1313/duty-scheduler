package com.dutyscheduler.duty.domain;

import java.util.Map;
import java.util.Set;

/**
 * The unit's standing limits, in one place.
 */
public final class DutyRules {

    private DutyRules() {
    }

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

    /**
     * Whether {@code next} may follow {@code previous} with no break between: the
     * same post, or a pair {@link #FREE_AFTER} allows.
     */
    public static boolean followsWithoutBreak(Post previous, Post next) {
        return previous == next || FREE_AFTER.getOrDefault(previous, Set.of()).contains(next);
    }

    /**
     * Returns the maximum number of stints a trooper can take given the number of
     * free hours available
     */
    public static int stintCapacity(int free) {
        return free - free / (MAX_STINT + MIN_BREAK);
    }

    /**
     * The span of hours worked, including any gaps of less than
     * {@link DutyRules#REST_GAP} hours.
     */
    public static int spanOf(Post[] cells) {
        int first = -1, previous = -1, total = 0;
        for (int slot = 0; slot < cells.length; slot++) {
            if (cells[slot] == null)
                continue;
            if (first < 0)
                first = slot;
            else if (slot - previous - 1 >= REST_GAP) {
                total += previous - first + 1;
                first = slot;
            }
            previous = slot;
        }
        return first < 0 ? 0 : total + previous - first + 1;
    }

    /**
     * How many times a row walks between AP and GG within one turnout.
     *
     * <p>
     * Consecutive duty hours are compared, breaks skipped, so "3h GG, an hour
     * off, 1h AP, an hour off, 3h GG" counts two. A gap of {@link #REST_GAP} or
     * more starts a new turnout and resets it: whoever stands GG after going
     * home has not walked from AP. Only AP and GG count — AP to PAC at 0600 is
     * the designed handover off the silent hours, not a walk to be avoided.
     */
    public static int postChanges(Post[] cells) {
        int changes = 0;
        Post last = null;
        int lastSlot = -1;
        for (int slot = 0; slot < cells.length; slot++) {
            Post post = cells[slot];
            if (post == null) {
                continue;
            }
            if (last != null && slot - lastSlot - 1 >= REST_GAP) {
                last = null;
            }
            if (last != null && last != post && isApOrGg(last) && isApOrGg(post)) {
                changes++;
            }
            last = post;
            lastSlot = slot;
        }
        return changes;
    }

    private static boolean isApOrGg(Post post) {
        return post == Post.AP || post == Post.GG;
    }

    /** Span beyond what these hours could possibly have been done in. */
    public static int excessSpan(Post[] cells) {
        int hours = 0;
        for (Post cell : cells)
            if (cell != null)
                hours++;
        return Math.max(0, spanOf(cells) - tightestSpan(hours));
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
