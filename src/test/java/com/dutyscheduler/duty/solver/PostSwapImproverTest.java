package com.dutyscheduler.duty.solver;

import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.DutyRules;
import com.dutyscheduler.duty.domain.Post;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Like {@link SpanImproverTest}, these assert the move's guarantees — fewer
 * walks, nobody's hours moved, nothing made illegal — rather than one exact
 * grid, so a better move later does not read as a regression.
 */
class PostSwapImproverTest {

    private final PostSwapImprover improver = new PostSwapImprover();

    /** "GG . AP" — one token per slot from slot 0, the rest of the sheet off. */
    private static Post[] row(String spec) {
        Post[] cells = new Post[DutyDay.SLOT_COUNT];
        String[] tokens = spec.trim().split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            cells[i] = tokens[i].equals(".") ? null : Post.valueOf(tokens[i]);
        }
        return cells;
    }

    private static int walks(Post[][] grid) {
        return Arrays.stream(grid).mapToInt(DutyRules::postChanges).sum();
    }

    /** Which hours each row is on duty, ignoring which post. */
    private static String onOff(Post[][] grid) {
        StringBuilder out = new StringBuilder();
        for (Post[] r : grid) {
            for (Post p : r) {
                out.append(p == null ? '.' : '#');
            }
            out.append('|');
        }
        return out.toString();
    }

    /** At every hour, how many stand each post. */
    private static String manning(Post[][] grid) {
        StringBuilder out = new StringBuilder();
        for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
            int ap = 0, gg = 0, pac = 0, bus = 0;
            for (Post[] r : grid) {
                if (r[slot] == Post.AP) ap++;
                if (r[slot] == Post.GG) gg++;
                if (r[slot] == Post.PAC) pac++;
                if (r[slot] == Post.BUS) bus++;
            }
            out.append(ap).append(gg).append(pac).append(bus).append(' ');
        }
        return out.toString();
    }

    @Test
    @DisplayName("a one-hour hop to AP is traded away when the other Trooper can take it")
    void removesAHop() {
        // A walks GG, AP, GG. B is on GG at that same hour, with a break either
        // side, so the two can trade it cleanly.
        Post[][] grid = {
                row("GG GG GG .  AP .  GG GG GG"),
                row(".  .  .  .  GG .  .  .  . "),
        };
        Post[][] after = improver.improve(grid);

        assertEquals(2, walks(grid));
        assertEquals(0, DutyRules.postChanges(after[0]), "A stays on GG all turnout");
        assertTrue(walks(after) < walks(grid));
    }

    @Test
    @DisplayName("hours, span and each hour's manning are exactly what they were")
    void movesNoHours() {
        Post[][] grid = {
                row("GG GG GG .  AP .  GG GG GG"),
                row("AP AP AP .  GG .  AP AP AP"),
        };
        Post[][] after = improver.improve(grid);

        assertEquals(onOff(grid), onOff(after), "nobody's hours moved");
        assertEquals(manning(grid), manning(after), "every hour still has the same posts manned");
        for (int t = 0; t < grid.length; t++) {
            assertEquals(DutyRules.spanOf(grid[t]), DutyRules.spanOf(after[t]));
        }
    }

    @Test
    @DisplayName("a trade that would put AP straight after GG with no break is refused")
    void refusesAnIllegalChange() {
        // A's hop at slot 4 could only go to B, but B stands GG either side of
        // it with no break, so taking AP there would be an illegal change.
        Post[][] grid = {
                row("GG .  .  .  AP .  GG"),
                row(".  .  .  GG GG GG . "),
        };
        Post[][] after = improver.improve(grid);

        assertArrayEquals(grid[0], after[0]);
        assertArrayEquals(grid[1], after[1]);
    }

    @Test
    @DisplayName("it never adds a walk, and never makes a change the rules forbid")
    void neverWorseNeverIllegal() {
        Post[][] grid = {
                row("AP AP .  GG GG .  AP .  GG GG GG"),
                row("GG GG .  AP AP .  GG .  AP AP AP"),
                row(".  .  GG .  .  AP .  AP .  .  . "),
        };
        Post[][] after = improver.improve(grid);

        assertTrue(walks(after) <= walks(grid));
        for (Post[] r : after) {
            for (int slot = 1; slot < r.length; slot++) {
                if (r[slot - 1] != null && r[slot] != null) {
                    assertTrue(DutyRules.followsWithoutBreak(r[slot - 1], r[slot]),
                            "illegal change at slot " + slot + ": " + Arrays.toString(r));
                }
            }
        }
    }

    @Test
    @DisplayName("it leaves the input alone and hands back a copy")
    void doesNotMutate() {
        Post[][] grid = {
                row("GG GG GG .  AP .  GG GG GG"),
                row(".  .  .  .  GG .  .  .  . "),
        };
        Post[] firstBefore = grid[0].clone();
        improver.improve(grid);
        assertArrayEquals(firstBefore, grid[0]);
    }
}
