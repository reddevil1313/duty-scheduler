package com.dutyscheduler.duty.solver;

import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.DutyRules;
import com.dutyscheduler.duty.domain.Post;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Local search, used as a repair step.
 *
 * It takes the finished sheet and hill-climbs on it.
 *
 * <p>The move is a swap of two equal-length segments between two men.
 * Every candidate swap is re-checked for stint length, legal transitions, 
 * availability and group before it is taken, and it is only taken if
 * the score strictly improves — so the sheet that comes out is still legal 
 * and never worse than the one that went in.
 */
final class SpanImprover {

    /** Whether a man may stand a post at an hour at all — availability and group. */
    interface Eligibility {
        boolean allows(int trooper, int slot);
    }

    /** How many passes of hill-climbing to do before giving up. */
    private static final int MAX_PASSES = 12;

    private final List<Trooper> troopers;
    private final Eligibility eligibility;

    SpanImprover(List<Trooper> troopers, Eligibility eligibility) {
        this.troopers = troopers;
        this.eligibility = eligibility;
    }

    /** 
     * Improves the schedule by swapping segments of duty between troopers, hill-climbing on wasted span.
     */
    Post[][] improve(Post[][] original) {
        Post[][] grid = copy(original);
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            if (!onePass(grid)) {
                return grid;
            }
        }
        return grid;
    }

    private boolean onePass(Post[][] grid) {
        List<Segment> segments = segments(grid);
        for (Segment a : segments) {
            for (Segment b : segments) {
                if (a.trooper >= b.trooper || a.length != b.length) {
                    continue;
                }
                if (trySwap(grid, a, b)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean trySwap(Post[][] grid, Segment a, Segment b) {
        int before = pairCost(grid, a.trooper, b.trooper);
        if (before == 0) {
            return false;                      // nothing to gain between these two
        }
        Post[] rowA = grid[a.trooper].clone();
        Post[] rowB = grid[b.trooper].clone();

        // Clear the slots that are being swapped out, so they don't block the swap.
        for (int k = 0; k < a.length; k++) {
            rowA[a.from + k] = null;
            rowB[b.from + k] = null;
        }

        // Move the segments into the other row, checking that they are free and legal.
        for (int k = 0; k < a.length; k++) {
            if (rowA[b.from + k] != null || rowB[a.from + k] != null) {
                return false;                  // the slots they would move into are taken
            }
            rowA[b.from + k] = grid[b.trooper][b.from + k];
            rowB[a.from + k] = grid[a.trooper][a.from + k];
        }
        if (!legal(a.trooper, rowA) || !legal(b.trooper, rowB)) {
            return false;
        }
        int after = cost(a.trooper, rowA) + cost(b.trooper, rowB);
        int worstBefore = Math.max(cost(a.trooper, grid[a.trooper]), cost(b.trooper, grid[b.trooper]));
        int worstAfter = Math.max(cost(a.trooper, rowA), cost(b.trooper, rowB));

        // Same rule as the score: never flatten one man's day by wrecking another's.
        if (worstAfter > worstBefore || after >= before) {
            return false;
        }
        grid[a.trooper] = rowA;
        grid[b.trooper] = rowB;
        return true;
    }

    private int pairCost(Post[][] grid, int a, int b) {
        return cost(a, grid[a]) + cost(b, grid[b]);
    }

    /** Wasted span for one row, or zero for a stay-out, who is on camp regardless. */
    private int cost(int trooper, Post[] row) {
        if (troopers.get(trooper).stayOut()) { // stay-out troopers don't count toward excess span
            return 0;
        }
        Schedule.Row scheduleRow = new Schedule.Row(troopers.get(trooper), List.of(row));
        int span = scheduleRow.span();
        int hours = scheduleRow.hours();
        
        return Math.max(0, span - DutyRules.tightestSpan(hours));
    }

    /** Re-checks every hard rule a swap could have broken. */
    private boolean legal(int trooper, Post[] row) {
        int slot = 0;
        while (slot < DutyDay.SLOT_COUNT) {
            if (row[slot] == null) {
                slot++;
                continue;
            }
            if (!eligibility.allows(trooper, slot)) {
                return false;
            }
            int start = slot;
            while (slot < DutyDay.SLOT_COUNT && row[slot] != null) {
                if (!eligibility.allows(trooper, slot)) {
                    return false;
                }
                if (slot > start) {
                    Post previous = row[slot - 1];
                    Post current = row[slot];
                    if (previous != current
                            && !DutyRules.FREE_AFTER.getOrDefault(previous, Set.of()).contains(current)) {
                        return false;
                    }
                }
                slot++;
            }
            if (slot - start > DutyRules.MAX_STINT) {
                return false;
            }
        }
        return true;
    }

    /** Every contiguous block of duty, and every sub-block of it, as a swappable unit. */
    private List<Segment> segments(Post[][] grid) {
        List<Segment> out = new ArrayList<>();
        for (int t = 0; t < grid.length; t++) {
            int slot = 0;
            while (slot < DutyDay.SLOT_COUNT) {
                if (grid[t][slot] == null) {
                    slot++;
                    continue;
                }
                int start = slot;
                while (slot < DutyDay.SLOT_COUNT && grid[t][slot] != null) {
                    slot++;
                }
                for (int from = start; from < slot; from++) {
                    for (int to = from; to < slot && to - from < DutyRules.MAX_STINT; to++) {
                        out.add(new Segment(t, from, to - from + 1));
                    }
                }
            }
        }
        return out;
    }

    private static Post[][] copy(Post[][] grid) {
        Post[][] out = new Post[grid.length][];
        for (int i = 0; i < grid.length; i++) {
            out[i] = grid[i].clone();
        }
        return out;
    }

    /** A contiguous block of duty on one row, and its location. */
    private record Segment(int trooper, int from, int length) {
    }
}
