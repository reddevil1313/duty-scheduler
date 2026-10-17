package com.dutyscheduler.duty.solver;

import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.DutyRules;
import com.dutyscheduler.duty.domain.Post;

/**
 * Cuts AP/GG walks. Two Troopers on duty through the same hours trade posts
 * for those hours, so hours, span and manning can't change. That's why it is
 * safe to run after {@link SpanImprover}.
 *
 * <p>
 * Only the post-change rule needs re-checking. A trade must strictly reduce
 * walks, so it always terminates.
 */
final class PostSwapImprover {

    /** One trade per pass. */
    private static final int MAX_PASSES = 200;

    /** [trooper][slot]: pinned cells, never traded. */
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

    /** First trade that helps, in a fixed order so results are repeatable. */
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
            // Both must be on for every traded hour.
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
