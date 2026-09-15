package com.dutyscheduler.duty.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A finished schedule for a day. Each row is one trooper's duty for that day.
 * 
 * @param day The day of the schedule.
 * @param rows The rows of the schedule, each row is one trooper's duty for that day.
 */
public record Schedule(DutyDay day, List<Row> rows) {

    public record Row(Trooper trooper, List<Post> posts) {
        public Row {
            if (posts.size() != DutyDay.SLOT_COUNT) {
                throw new IllegalArgumentException(trooper.name() + " has " + posts.size() + " cells, expected " + DutyDay.SLOT_COUNT);
            }

            // posts can contain nulls
            posts = Collections.unmodifiableList(new ArrayList<>(posts));
        }

        public Post at(int index) {
            return posts.get(index);
        }

        /** Hours worked */
        public int hours() {
            return (int) posts.stream().filter(Objects::nonNull).count();
        }
    }

    public Schedule {
        rows = List.copyOf(rows);
    }

    public Optional<Row> row(Trooper trooper) {
        return rows.stream().filter(r -> r.trooper().equals(trooper)).findFirst();
    }

    public int hours(Trooper trooper) {
        return row(trooper).map(Row::hours).orElse(0);
    }

     /** Which Posts are manned at a given slot, and how many troopers are manning each */
    public Map<Post, Integer> manningAt(int slot) {
        Map<Post, Integer> out = new LinkedHashMap<>();
        for (Row r : rows) {
            Post p = r.at(slot);
            if (p != null) {
                out.merge(p, 1, Integer::sum);
            }
        }
        return Map.copyOf(out);
    }

    /** Generate a copyof the schedule with one cell changed. For testing purposes. */
    public Schedule with(Trooper trooper, int slot, Post post) {
        List<Row> next = new ArrayList<>();
        for (Row r : rows) {
            if (!r.trooper().equals(trooper)) {
                next.add(r);
                continue;
            }
            List<Post> cells = new ArrayList<>(r.posts());
            cells.set(slot, post);
            next.add(new Row(r.trooper(), cells));
        }
        return new Schedule(day, next);
    }

    public static Builder builder(DutyDay day) {
        return new Builder(day);
    }

    public static class Builder {
        private final DutyDay day;
        private final List<Row> rows = new ArrayList<>();

        public Builder(DutyDay day) {
            this.day = day;
        }

        /**
         * A row for a trooper, with a string of 24 cells, each cell being either a post name or "." for off.
         * 
         * <pre>{@code ". . GG GG . AP AP AP . . . . . . . . . . . . . . . ."}</pre>
         */
        public Builder row(Trooper trooper, String spec) {
            String[] tokens = spec.trim().split("\\s+");
            if (tokens.length != DutyDay.SLOT_COUNT) {
                throw new IllegalArgumentException(
                        trooper.name() + ": expected " + DutyDay.SLOT_COUNT
                                + " cells, got " + tokens.length + " in " + Arrays.toString(tokens));
            }
            if (rows.stream().anyMatch(r -> r.trooper().equals(trooper))) {
                throw new IllegalArgumentException(trooper.name() + " already has a row on this sheet.");
            }
            List<Post> posts = new ArrayList<>(DutyDay.SLOT_COUNT);
            for (String token : tokens) {
                posts.add(token.equals(".") || token.equals("-") ? null : Post.valueOf(token));
            }
            rows.add(new Row(trooper, posts));
            return this;
        }

        /** A row for a trooper, with a list of posts, one per hour. Null means off. */
        public Builder row(Trooper trooper, List<Post> posts) {
            if (rows.stream().anyMatch(r -> r.trooper().equals(trooper))) {
                // Two rows for one man is always a mistake, and a quiet one: the
                // hours would come back from whichever row was added first, and a
                // change aimed at one of them would land on both.
                throw new IllegalArgumentException(
                        trooper.name() + " already has a row on this sheet.");
            }
            rows.add(new Row(trooper, posts));
            return this;
        }

        /** A row with nobody on anything — a man on the sheet but off all day. */
        public Builder empty(Trooper trooper) {
            return row(trooper, ". ".repeat(DutyDay.SLOT_COUNT).trim());
        }

        public Schedule build() {
            return new Schedule(day, rows);
        }
    }
    
}
