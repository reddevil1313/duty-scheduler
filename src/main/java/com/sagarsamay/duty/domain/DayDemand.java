package com.sagarsamay.duty.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link Demand} resolved across one whole sheet: what each of the twenty-four
 * slots needs, and what that adds up to.
 *
 * <p>{@link #postHours()} is the number of ST-hours the sheet has to absorb, and
 * it is the first number worth knowing about any day — 47 on a weekday, 44 on a
 * Friday, 48 on a Saturday, 51 on a Sunday.
 */
public record DayDemand(DutyDay day, List<Map<Post, Integer>> byHour) {

    public DayDemand {
        if (byHour.size() != DutyDay.SLOT_COUNT) {
            throw new IllegalArgumentException(
                    "A sheet has " + DutyDay.SLOT_COUNT + " slots, got " + byHour.size());
        }
        byHour = List.copyOf(byHour);
    }

    public static DayDemand of(DutyDay day) {
        List<Map<Post, Integer>> hours = new ArrayList<>(DutyDay.SLOT_COUNT);
        for (Slot s : day.slots()) {
            hours.add(Demand.at(s.start()));
        }
        return new DayDemand(day, hours);
    }

    public Map<Post, Integer> at(int slot) {
        return byHour.get(slot);
    }

    /** How many STs this hour needs across all its posts. */
    public int headcountAt(int slot) {
        return at(slot).values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Total ST-hours over the sheet. */
    public int postHours() {
        int total = 0;
        for (int i = 0; i < DutyDay.SLOT_COUNT; i++) {
            total += headcountAt(i);
        }
        return total;
    }

    /** True if any hour of this sheet falls inside the weekend block. */
    public boolean touchesWeekend() {
        return day.slots().stream().anyMatch(s -> Demand.isWeekend(s.start()));
    }

    /** True if any hour of this sheet stands a day post — PAC or BUS. */
    public boolean hasDayPosts() {
        return byHour.stream().anyMatch(m -> m.containsKey(Post.PAC) || m.containsKey(Post.BUS));
    }
}
