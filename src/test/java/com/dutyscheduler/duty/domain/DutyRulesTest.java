package com.dutyscheduler.duty.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DutyRulesTest {

    /** "GG . AP" — one token per slot from slot 0, the rest of the sheet off. */
    private static Post[] row(String spec) {
        Post[] cells = new Post[DutyDay.SLOT_COUNT];
        String[] tokens = spec.trim().split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            cells[i] = tokens[i].equals(".") ? null : Post.valueOf(tokens[i]);
        }
        return cells;
    }

    @Nested
    @DisplayName("post changes")
    class PostChanges {

        @Test
        @DisplayName("3h GG, 1h AP, 3h GG is two walks: there and back")
        void theWalkThePlanIsAbout() {
            assertEquals(2, DutyRules.postChanges(row("GG GG GG . AP . GG GG GG")));
        }

        @Test
        @DisplayName("staying on one post, breaks and all, is no walk")
        void samePostIsFree() {
            assertEquals(0, DutyRules.postChanges(row("GG GG GG . GG GG . GG")));
        }

        @Test
        @DisplayName("AP to PAC is the 0600 handover, not a walk")
        void apToPacIsNotCounted() {
            assertEquals(0, DutyRules.postChanges(row("AP AP PAC PAC . PAC")));
        }

        @Test
        @DisplayName("a change across a rest gap is a new turnout, not a walk")
        void restResets() {
            // AP at slot 0, GG at slot 11: ten hours off between, so home in between.
            assertEquals(0, DutyRules.postChanges(row("AP . . . . . . . . . . GG")));
            // Nine hours off is not rest, so it still counts.
            assertEquals(1, DutyRules.postChanges(row("AP . . . . . . . . . GG")));
        }

        @Test
        @DisplayName("an empty row walks nowhere")
        void emptyRow() {
            assertEquals(0, DutyRules.postChanges(new Post[DutyDay.SLOT_COUNT]));
        }
    }

    @Test
    @DisplayName("only the same post, or AP and PAC either way round, follow with no break")
    void followsWithoutBreak() {
        assertTrue(DutyRules.followsWithoutBreak(Post.GG, Post.GG));
        assertTrue(DutyRules.followsWithoutBreak(Post.AP, Post.PAC));
        assertTrue(DutyRules.followsWithoutBreak(Post.PAC, Post.AP));
        assertFalse(DutyRules.followsWithoutBreak(Post.AP, Post.GG));
        assertFalse(DutyRules.followsWithoutBreak(Post.GG, Post.AP));
        assertFalse(DutyRules.followsWithoutBreak(Post.BUS, Post.PAC));
    }
}
