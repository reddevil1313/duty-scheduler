package com.sagarsamay.duty.rules;

import com.sagarsamay.duty.domain.DutyDay;
import com.sagarsamay.duty.domain.DutyRules;
import com.sagarsamay.duty.domain.Trooper;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How many hours each ST is meant to do on a sheet, before anyone is placed on a
 * post.
 *
 * <p>With a target per man, placing someone is a
 * decision that can be checked against a budget instead of a choice whose
 * consequences only show up twenty decisions later.
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

    /** The same within one group, which is where the plus-or-minus-one really holds. */
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
     * Splits a sheet's demand between the night group and everyone else, and
     * shares each half out evenly.
     *
     * <p>The two groups are budgeted separately because they are not working the
     * same hours: the night group has the silent block and the day group has the
     * rest. Pooling them and dividing by eleven would hand the night group more
     * hours than the silent block contains.
     * 
     * @param history cumulative hours each trooper has done so far, to break ties when
     *                the total does not divide evenly
     */
    public static HourTargets allocate(DutyDay day,
                                       List<Trooper> nightGroup,
                                       List<Trooper> dayGroup,
                                       Map<Trooper, Integer> history) {
        int nightDemand = Availability.demandWithin(day, Availability.silentSlots(day));
        int dayDemand = Availability.demandWithin(day, Availability.daySlots(day));

        Map<Trooper, Integer> out = new LinkedHashMap<>();
        out.putAll(distribute(nightDemand, nightGroup, history));
        out.putAll(distribute(dayDemand, dayGroup, history));
        return new HourTargets(out, nightDemand, dayDemand);
    }

    /**
     * Shares {@code total} hours over a group as evenly as it will go, so no two
     * targets differ by more than one.
     *
     * <p>When it does not divide evenly somebody has to take the extra hour, and
     * the tie is broken on cumulative history: whoever has done least so far goes
     * first. That is the only place long-run fairness enters — no single sheet can
     * be fair on its own, but a week of them can be.
     */
    public static Map<Trooper, Integer> distribute(int total,
                                                   List<Trooper> group,
                                                   Map<Trooper, Integer> history) {
        if (group.isEmpty()) {
            return Map.of();
        }

        int base = total / group.size();
        int extra = total % group.size();

        // Sort the group by cumulative history, then by name to break ties.
        List<Trooper> order = group.stream()
                .sorted(Comparator
                        .comparingInt((Trooper t) -> history.getOrDefault(t, 0))
                        .thenComparing(Trooper::name))
                .toList();

        // The first {@code extra} people in the sorted order get one more hour than
        // the rest, so the spread is at most one.
        Map<Trooper, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            out.put(order.get(i), base + (i < extra ? 1 : 0));
        }
        return out;
    }
}
