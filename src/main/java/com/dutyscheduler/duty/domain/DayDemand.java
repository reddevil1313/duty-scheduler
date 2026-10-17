package com.dutyscheduler.duty.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link Demand} for each of a sheet's 24 slots. {@link #postHours()} is 47 on
 * a weekday, 44 on Friday, 48 on Saturday and 51 on Sunday.
 */
public record DayDemand(DutyDay day, List<Map<Post, Integer>> byHour) {

    public DayDemand {
        if (byHour.size() != DutyDay.SLOT_COUNT) {
            throw new IllegalArgumentException(
                    "A sheet has " + DutyDay.SLOT_COUNT + " slots, got " + byHour.size());
        }
        byHour = List.copyOf(byHour);
    }

    /**
     * Creates a {@link DayDemand} for the given {@link DutyDay}.
     */
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

    /** How many Troopers this hour needs across all its posts. */
    public int headcountAt(int slot) {
        return at(slot).values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Total Trooper-hours over the sheet. */
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

    /** True if any hour of this sheet has PAC or BUS. */
    public boolean hasDayPosts() {
        return byHour.stream().anyMatch(m -> m.containsKey(Post.PAC) || m.containsKey(Post.BUS));
    }
}
