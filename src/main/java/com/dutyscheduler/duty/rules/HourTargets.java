package com.dutyscheduler.duty.rules;

import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.DutyRules;
import com.dutyscheduler.duty.domain.Trooper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How many hours each Trooper should do on a sheet, decided before the search
 * so each placement can be checked against a budget.
 */
public record HourTargets(Map<Trooper, Integer> hours, int nightDemand, int dayDemand) {

    public HourTargets {
        hours = Map.copyOf(hours);
    }

    public int forTrooper(Trooper trooper) {
        return hours.getOrDefault(trooper, 0);
    }

    public int total() {
        return hours.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Highest target minus lowest, across everyone who has one. */
    public int spread() {
        return spreadOf(hours.values());
    }

    /** Spread within one group, where the +/-1 guarantee holds. */
    public int spreadWithin(List<Trooper> group) {
        return spreadOf(group.stream().map(this::forTrooper).toList());
    }

    private static int spreadOf(java.util.Collection<Integer> values) {
        if (values.isEmpty()) {
            return 0;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        return max - min;
    }

    public boolean withinCap() {
        return hours.values().stream().allMatch(h -> h <= DutyRules.HOUR_CAP);
    }

    /**
     * Splits demand between the night and day groups and shares each out
     * evenly. Separate budgets, because the groups work different hours.
     *
     * @param history past hours per trooper, to break ties
     */
    public static HourTargets allocate(DutyDay day,
            List<Trooper> nightGroup,
            List<Trooper> dayGroup,
            Map<Trooper, Integer> history) {
        return allocate(day, nightGroup, dayGroup, List.of(), history);
    }

    public static HourTargets allocate(DutyDay day,
            List<Trooper> nightGroup,
            List<Trooper> dayGroup,
            List<Absence> absences,
            Map<Trooper, Integer> history) {
        return allocate(day, nightGroup, dayGroup, absences, history, 0);
    }

    public static HourTargets allocate(DutyDay day,
            List<Trooper> nightGroup,
            List<Trooper> dayGroup,
            List<Absence> absences,
            Map<Trooper, Integer> history,
            int crossover) {
        boolean[] silent = Availability.silentSlots(day);
        boolean[] daytime = Availability.daySlots(day);
        int nightDemand = Availability.demandWithin(day, silent) + crossover;
        int dayDemand = Availability.demandWithin(day, daytime) - crossover;

        Map<Trooper, Integer> out = new LinkedHashMap<>();
        // The crossover hours need somewhere to go.
        boolean[] nightWindow = silent;
        if (crossover > 0) {
            nightWindow = silent.clone();
            boolean[] cross = crossoverWindow(day);
            for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
                nightWindow[slot] = nightWindow[slot] || cross[slot];
            }
        }
        out.putAll(share(nightDemand, nightGroup, day, nightWindow, absences, history));
        out.putAll(share(dayDemand, dayGroup, day, daytime, absences, history));
        return new HourTargets(out, nightDemand, dayDemand);
    }

    /** The few hours immediately after the silent block ends. */
    public static boolean[] crossoverWindow(DutyDay day) {
        boolean[] silent = Availability.silentSlots(day);
        boolean[] out = new boolean[DutyDay.SLOT_COUNT];
        int last = -1;
        for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
            if (silent[slot]) {
                last = slot;
            }
        }
        for (int k = 1; k <= DutyRules.CROSS_WINDOW; k++) {
            int slot = last + k;
            if (slot < DutyDay.SLOT_COUNT && !silent[slot]) {
                out[slot] = true;
            }
        }
        return out;
    }

    /** An even share, capped at each Trooper's room. */
    private static Map<Trooper, Integer> share(int total,
            List<Trooper> group,
            DutyDay day,
            boolean[] window,
            List<Absence> absences,
            Map<Trooper, Integer> history) {
        Map<Trooper, Integer> room = new LinkedHashMap<>();
        for (Trooper trooper : group) {
            int capacity = Math.min(DutyRules.HOUR_CAP,
                    Availability.capacityWithin(Availability.mask(trooper, day, absences), window));
            if (capacity > 0) {
                room.put(trooper, capacity);
            }
        }

        Map<Trooper, Integer> out = new LinkedHashMap<>();
        List<Trooper> pool = new ArrayList<>(room.keySet());
        int left = total;
        while (!pool.isEmpty()) {
            Map<Trooper, Integer> proposed = distribute(left, pool, history);
            // Cap anyone over their room and share the rest again.
            List<Trooper> overflowing = pool.stream()
                    .filter(t -> proposed.get(t) > room.get(t))
                    .toList();
            if (overflowing.isEmpty()) {
                out.putAll(proposed);
                return out;
            }
            for (Trooper trooper : overflowing) {
                out.put(trooper, room.get(trooper));
                left -= room.get(trooper);
            }
            pool.removeAll(overflowing);
        }
        return out;
    }

    /**
     * Shares {@code total} so no two targets differ by more than one. Spare
     * hours go to whoever has done the fewest so far, which is what keeps
     * things fair over a week.
     */
    public static Map<Trooper, Integer> distribute(int total,
            List<Trooper> group,
            Map<Trooper, Integer> history) {
        if (group.isEmpty()) {
            return Map.of();
        }
        int base = total / group.size();
        int extra = total % group.size();

        List<Trooper> order = group.stream()
                .sorted(Comparator
                        .comparingInt((Trooper t) -> history.getOrDefault(t, 0))
                        .thenComparing(Trooper::name))
                .toList();

        Map<Trooper, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            out.put(order.get(i), base + (i < extra ? 1 : 0));
        }
        return out;
    }
}
