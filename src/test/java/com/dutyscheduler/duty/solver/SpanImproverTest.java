package com.dutyscheduler.duty.solver;

import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.DutyRules;
import com.dutyscheduler.duty.domain.Post;
import com.dutyscheduler.duty.domain.Trooper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the guarantees, not an exact grid: never worse, still legal, same hours. */
class SpanImproverTest {

    private static final Trooper A = Trooper.of("A");
    private static final Trooper B = Trooper.of("B");
    private static final List<Trooper> BOTH = List.of(A, B);

    /** Everything allowed. */
    private static final SpanImprover.Eligibility ANYWHERE = (trooper, slot) -> true;

    private static Post[] cells(int... slots) {
        Post[] row = new Post[DutyDay.SLOT_COUNT];
        for (int slot : slots) {
            row[slot] = Post.PAC;
        }
        return row;
    }

    private static int hours(Post[] row) {
        int n = 0;
        for (Post p : row) {
            if (p != null) {
                n++;
            }
        }
        return n;
    }

    private static int span(Post[] row) {
        int first = -1;
        int previous = -1;
        int total = 0;
        for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
            if (row[slot] == null) {
                continue;
            }
            if (first < 0) {
                first = slot;
            } else if (slot - previous - 1 >= DutyRules.REST_GAP) {
                total += previous - first + 1;
                first = slot;
            }
            previous = slot;
        }
        return first < 0 ? 0 : total + previous - first + 1;
    }

    private static int excess(Post[][] grid) {
        int total = 0;
        for (Post[] row : grid) {
            total += Math.max(0, span(row) - DutyRules.tightestSpan(hours(row)));
        }
        return total;
    }

    @Test
    @DisplayName("tightens span without changing hours")
    void tightensWithoutChangingHourCounts() {
        // A is spread across the afternoon; B is compact.
        Post[][] before = { cells(10, 14), cells(11, 12) };
        int hoursA = hours(before[0]);
        int hoursB = hours(before[1]);

        Post[][] after = new SpanImprover(BOTH, ANYWHERE).improve(before);

        assertTrue(excess(after) < excess(before),
                "expected an improvement on " + excess(before) + ", got " + excess(after));
        assertEquals(hoursA, hours(after[0]), "A's hours must not change");
        assertEquals(hoursB, hours(after[1]), "B's hours must not change");
    }

    @Test
    @DisplayName("returns a copy")
    void doesNotMutateTheInput() {
        Post[][] before = { cells(10, 22), cells(12, 14) };
        Post[] originalRowA = before[0].clone();

        new SpanImprover(BOTH, ANYWHERE).improve(before);

        assertArrayEquals(originalRowA, before[0]);
    }

    @Test
    @DisplayName("no run over three, no illegal change")
    void keepsEveryRowLegal() {
        Post[][] before = { cells(10, 11, 13, 14, 22), cells(12, 15, 16, 17, 20) };

        Post[][] after = new SpanImprover(BOTH, ANYWHERE).improve(before);

        for (Post[] row : after) {
            int run = 0;
            for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
                run = row[slot] == null ? 0 : run + 1;
                assertTrue(run <= DutyRules.MAX_STINT, "a run of " + run + " hours");
                if (run > 1) {
                    Post previous = row[slot - 1];
                    Post current = row[slot];
                    assertTrue(previous == current
                            || DutyRules.FREE_AFTER.getOrDefault(previous, Set.of()).contains(current),
                            previous + " into " + current + " with no break");
                }
            }
        }
    }

    @Test
    @DisplayName("respects eligibility")
    void respectsEligibility() {
        Post[][] before = { cells(10, 22), cells(12, 14) };
        // A can only be on at 10 and 22.
        SpanImprover.Eligibility pinned = (trooper, slot) -> trooper != 0 || slot == 10 || slot == 22;

        Post[][] after = new SpanImprover(BOTH, pinned).improve(before);

        assertArrayEquals(before[0], after[0], "A could not legally be moved, so they were not");
    }

    @Test
    @DisplayName("nothing to gain: unchanged")
    void alreadyTightIsLeftAlone() {
        Post[][] before = { cells(10, 11, 12), cells(14, 15, 16) };

        Post[][] after = new SpanImprover(BOTH, ANYWHERE).improve(before);

        assertArrayEquals(before[0], after[0]);
        assertArrayEquals(before[1], after[1]);
    }
}
