package com.dutyscheduler.duty.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DutyRulesTest {

    /** "GG . AP": one token per slot from slot 0, rest off. */
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
        @DisplayName("GG, AP, GG is two walks")
        void theWalkThePlanIsAbout() {
            assertEquals(2, DutyRules.postChanges(row("GG GG GG . AP . GG GG GG")));
        }

        @Test
        @DisplayName("one post is no walk")
        void samePostIsFree() {
            assertEquals(0, DutyRules.postChanges(row("GG GG GG . GG GG . GG")));
        }

        @Test
        @DisplayName("AP to PAC is not a walk")
        void apToPacIsNotCounted() {
            assertEquals(0, DutyRules.postChanges(row("AP AP PAC PAC . PAC")));
        }

        @Test
        @DisplayName("no walk across a rest gap")
        void restResets() {
            // Ten hours off.
            assertEquals(0, DutyRules.postChanges(row("AP . . . . . . . . . . GG")));
            // Nine is not enough.
            assertEquals(1, DutyRules.postChanges(row("AP . . . . . . . . . GG")));
        }

        @Test
        @DisplayName("empty row")
        void emptyRow() {
            assertEquals(0, DutyRules.postChanges(new Post[DutyDay.SLOT_COUNT]));
        }
    }

    @Test
    @DisplayName("only same post or AP/PAC need no break")
    void followsWithoutBreak() {
        assertTrue(DutyRules.followsWithoutBreak(Post.GG, Post.GG));
        assertTrue(DutyRules.followsWithoutBreak(Post.AP, Post.PAC));
        assertTrue(DutyRules.followsWithoutBreak(Post.PAC, Post.AP));
        assertFalse(DutyRules.followsWithoutBreak(Post.AP, Post.GG));
        assertFalse(DutyRules.followsWithoutBreak(Post.GG, Post.AP));
        assertFalse(DutyRules.followsWithoutBreak(Post.BUS, Post.PAC));
    }
}
