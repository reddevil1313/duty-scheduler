package com.dutyscheduler.duty.solver;

import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.DutyRules;
import com.dutyscheduler.duty.domain.Post;

/**
 * Local search for one cost only: walks between AP and GG.
 *
 * <p>
 * The move: two Troopers both on duty through the same hours trade posts for
 * those hours. Nobody's hours move, so hours, span and every Trooper's on/off
 * pattern are exactly what they were, and each hour still has the same posts
 * manned. That is what lets it run after {@link SpanImprover} without risk to
 * anything ranked above post changes — it cannot touch them, by construction,
 * rather than by being careful.
 *
 * <p>
 * The only rule a trade can break is a post change with no break between, so
 * that is all it re-checks. A trade is taken only when it strictly reduces the
 * two Troopers' walks between them, so the search always ends.
 */
final class PostSwapImprover {

    /** Each pass takes one trade; a sheet has far fewer walks than this to remove. */
    private static final int MAX_PASSES = 200;

    /** [trooper][slot]: cells a human chose, such as the BUS hours. Never traded. */
    private final boolean[][] pinned;

    PostSwapImprover() {
        this(null);
    }

    PostSwapImprover(boolean[][] pinned) {
        this.pinned = pinned;
    }

    Post[][] improve(Post[][] original) {
        Post[][] grid = new Post[original.length][];
        for (int i = 0; i < original.length; i++) {
            grid[i] = original[i].clone();
        }
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            if (!onePass(grid)) {
                break;
            }
        }
        return grid;
    }

    /** Takes the first trade that helps, scanning in a fixed order so the result is repeatable. */
    private boolean onePass(Post[][] grid) {
        for (int a = 0; a < grid.length; a++) {
            for (int b = a + 1; b < grid.length; b++) {
                int before = DutyRules.postChanges(grid[a]) + DutyRules.postChanges(grid[b]);
                if (before == 0) {
                    continue;
                }
                for (int from = 0; from < DutyDay.SLOT_COUNT; from++) {
                    for (int length = 1; length <= DutyRules.MAX_STINT && from + length <= DutyDay.SLOT_COUNT; length++) {
                        if (tryTrade(grid, a, b, from, length, before)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean tryTrade(Post[][] grid, int a, int b, int from, int length, int before) {
        boolean differs = false;
        for (int slot = from; slot < from + length; slot++) {
            // Both on duty for every hour traded; otherwise the trade would move hours.
            if (grid[a][slot] == null || grid[b][slot] == null) {
                return false;
            }
            if (pinned != null && (pinned[a][slot] || pinned[b][slot])) {
                return false;
            }
            differs |= grid[a][slot] != grid[b][slot];
        }
        if (!differs) {
            return false;
        }
        Post[] rowA = grid[a].clone();
        Post[] rowB = grid[b].clone();
        for (int slot = from; slot < from + length; slot++) {
            rowA[slot] = grid[b][slot];
            rowB[slot] = grid[a][slot];
        }
        if (!changesLegal(rowA) || !changesLegal(rowB)) {
            return false;
        }
        if (DutyRules.postChanges(rowA) + DutyRules.postChanges(rowB) >= before) {
            return false;
        }
        grid[a] = rowA;
        grid[b] = rowB;
        return true;
    }

    /** Every change of post with no break between is one the rules allow. */
    private static boolean changesLegal(Post[] row) {
        for (int slot = 1; slot < row.length; slot++) {
            if (row[slot - 1] != null && row[slot] != null
                    && !DutyRules.followsWithoutBreak(row[slot - 1], row[slot])) {
                return false;
            }
        }
        return true;
    }
}
