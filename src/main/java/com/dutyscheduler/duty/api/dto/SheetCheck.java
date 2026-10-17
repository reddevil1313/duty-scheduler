package com.dutyscheduler.duty.api.dto;

import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.DayDemand;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Post;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;
import com.dutyscheduler.duty.rules.Availability;
import com.dutyscheduler.duty.rules.ValidationResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a sheet breaks, and what it was supposed to cover.
 *
 * @param issues rule violations from the validator, then any hours someone is
 *               on duty during their own leave. Empty means the sheet is clean.
 * @param demand per slot, how many Troopers each post needs. The board uses it
 *               to draw coverage and to offer only the posts that stand at
 *               the hour being edited, so it never re-derives the demand rules.
 * @param sheet  the sheet as checked, with hours and span worked out again.
 *               Span is rest-aware, which is domain logic the board should
 *               show rather than copy.
 * @param away   per Trooper away during this sheet, 24 entries: the kind of
 *               leave covering that hour, {@code SO} for a stay-out on a sheet
 *               with no hour they can stand (a weekend), or null where they
 *               are free. Troopers away no hour of it are left out. The board
 *               greys these hours, or the whole row when every hour is away.
 */
public record SheetCheck(List<Issue> issues, List<Map<Post, Integer>> demand, ScheduleResponse sheet,
        Map<String, List<String>> away) {

    /**
     * @param rule    a validator {@code Rule} name, or {@code ON_LEAVE}
     * @param trooper who it concerns, when it concerns one person
     * @param slot    the first hour it concerns, when it concerns one hour
     */
    public record Issue(String rule, String trooper, Integer slot, String message) {
    }

    public static final String ON_LEAVE = "ON_LEAVE";

    /** A stay-out with no hour of this sheet open to them: the weekend, when they are not in. */
    public static final String STAY_OUT = "SO";

    public static SheetCheck of(Schedule schedule, ValidationResult result, List<Issue> leaveClashes,
            List<Absence> absences) {
        DutyDay day = schedule.day();
        List<Issue> issues = new ArrayList<>();
        result.violations().forEach(v -> issues.add(
                new Issue(v.rule().name(), v.who(), v.slot(), v.message())));
        issues.addAll(leaveClashes);

        DayDemand demand = DayDemand.of(day);
        List<Map<Post, Integer>> bySlot = new ArrayList<>(DutyDay.SLOT_COUNT);
        for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
            Map<Post, Integer> needs = new EnumMap<>(Post.class);
            needs.putAll(demand.at(slot));
            bySlot.add(needs);
        }
        return new SheetCheck(List.copyOf(issues), List.copyOf(bySlot), ScheduleResponse.from(schedule),
                away(day, absences, schedule.rows().stream().map(Schedule.Row::trooper).toList()));
    }

    /**
     * Which hours each Trooper is away, and why. Where two bookings overlap,
     * the first listed wins. A stay-out who could stand no hour of this sheet
     * even with no leave at all — every weekend sheet — is away for every hour
     * leave does not already cover, shown as they would be on leave rather than
     * as an empty row nobody posted. Whether an hour is open is asked of
     * {@link Availability}, which owns the stay-out window; this does not know
     * which days are weekends.
     */
    static Map<String, List<String>> away(DutyDay day, List<Absence> absences, List<Trooper> troopers) {
        Map<String, String[]> byName = new LinkedHashMap<>();
        for (Absence absence : absences) {
            for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
                if (!absence.covers(day.slot(slot))) {
                    continue;
                }
                String[] hours = byName.computeIfAbsent(absence.trooper().name(),
                        n -> new String[DutyDay.SLOT_COUNT]);
                if (hours[slot] == null) {
                    hours[slot] = absence.kind().name();
                }
            }
        }
        for (Trooper trooper : troopers) {
            if (!trooper.stayOut() || openAtAll(Availability.mask(trooper, day, List.of()))) {
                continue;
            }
            String[] hours = byName.computeIfAbsent(trooper.name(), n -> new String[DutyDay.SLOT_COUNT]);
            for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
                if (hours[slot] == null) {
                    hours[slot] = STAY_OUT;
                }
            }
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        // Arrays.asList, not List.of: List.of refuses the nulls that mean "free".
        byName.forEach((name, hours) -> out.put(name, Collections.unmodifiableList(Arrays.asList(hours))));
        return Collections.unmodifiableMap(out);
    }

    private static boolean openAtAll(boolean[] mask) {
        for (boolean open : mask) {
            if (open) {
                return true;
            }
        }
        return false;
    }
}
