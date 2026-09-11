package com.dutyscheduler.duty.rules;

import com.dutyscheduler.duty.domain.DayDemand;
import com.dutyscheduler.duty.domain.Demand;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.DutyRules;
import com.dutyscheduler.duty.domain.Post;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Slot;


import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Re-derives what a sheet needs and checks a finished sheet against it, rule by
 * rule.
 */
public final class ScheduleValidator {
    public ValidationResult validate(Schedule schedule) {
        List<Violation> violations = new ArrayList<>();
        checkCoverage(schedule, violations);
        for (Schedule.Row row : schedule.rows()) {
            checkStintsAndTransitions(schedule, row, violations);
            checkStayOutWindow(schedule, row, violations);
            checkHourCap(row, violations);
        }
        return new ValidationResult(violations);
    }

    /**
     * Checks that every post is covered for every slot in the schedule. If any
     * post is not covered for any slot, adds a violation to the list.
     * @param schedule
     * @param violations
     */
    private void checkCoverage(Schedule schedule, List<Violation> violations) {
        DayDemand demand = DayDemand.of(schedule.day());
        for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
            Map<Post, Integer> required = demand.at(slot);
            Map<Post, Integer> actual = schedule.manningAt(slot);
            String label = schedule.day().slot(slot).label();

            Set<Post> posts = new HashSet<>(required.keySet());
            posts.addAll(actual.keySet());
            for (Post post : posts) {
                int need = required.getOrDefault(post, 0);
                int got = actual.getOrDefault(post, 0);
                if (got < need) {    
                    violations.add(Violation.at(Rule.UNDER_MANNED, slot,
                         label + ": " + post + " needs " + need + ", has " + got));
                } else if (got > need) {
                    violations.add(Violation.at(Rule.OVER_MANNED, slot,
                            need == 0
                                    ? label + ": " + post + " does not stand at this hour, but " + got + " on it"
                                    : label + ": " + post + " needs " + need + ", has " + got));
                }
            }
        }
    }

    /**
     * Checks that a trooper's stints are not too long, and that any transitions
     * between posts are legal. If any stint is too long or any transition is
     * illegal, adds a violation to the list.
     */
    private void checkStintsAndTransitions(Schedule schedule, Schedule.Row row, List<Violation> violations) {
        String who = row.trooper().name();
        int i = 0;
        while (i < DutyDay.SLOT_COUNT) {
            // Skip any hours where the trooper is not on duty.
            if (row.at(i) == null) {
                i++;
                continue;
            }

            // Find the length of the stint starting at this hour, and check it against the rules.
            int start = i;
            while (i < DutyDay.SLOT_COUNT && row.at(i) != null) {
                i++;
            }
            int length = i - start;
            String from = schedule.day().slot(start).label();

            if (length > DutyRules.MAX_STINT) {
                violations.add(Violation.by(Rule.STINT_TOO_LONG, who, start,
                        who + " is on for " + length + " hours from " + from
                                + "; the limit is " + DutyRules.MAX_STINT));
            }

            // Check that any transitions between posts are legal.
            for (int k = start + 1; k < start + length; k++) {
                Post previous = row.at(k - 1);
                Post current = row.at(k);
                if (previous == current) {
                    continue;
                }
                if (!DutyRules.FREE_AFTER.getOrDefault(previous, Set.of()).contains(current)) {
                    violations.add(Violation.by(Rule.ILLEGAL_TRANSITION, who, k,
                            who + " goes " + previous + " to " + current + " at "
                                    + schedule.day().slot(k).label() + " with no break"));
                }
            }
        }
    }

    /**
     * Checks that a trooper's stay-out is within the duty window and not in the
     * weekend block. If the stay-out is outside the duty window or in the weekend
     * block, adds a violation to the list.
     */
    private void checkStayOutWindow(Schedule schedule, Schedule.Row row, List<Violation> violations) {
        if (!row.trooper().stayOut()) {
            return;
        }
        String who = row.trooper().name();
        for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
            if (row.at(slot) == null) {
                continue;
            }
            Slot s = schedule.day().slot(slot);
            LocalDateTime t = s.start();
            if (Demand.isWeekend(t)) {
                violations.add(Violation.by(Rule.STAY_OUT_WINDOW, who, slot,
                        who + " is a stay-out and is posted at " + s.label()
                                + ", inside the weekend block"));
            } else if (t.getHour() < DutyRules.STAY_OUT_FROM || t.getHour() >= DutyRules.STAY_OUT_TO) {
                violations.add(Violation.by(Rule.STAY_OUT_WINDOW, who, slot,
                        who + " is a stay-out and is posted at " + s.label()
                                + ", outside " + DutyRules.STAY_OUT_FROM + "00-" + DutyRules.STAY_OUT_TO + "00"));
            }
        }
    }

    /**
     * Checks that a trooper's total hours of duty do not exceed the hour cap. If the
     * total hours exceed the hour cap, adds a violation to the list.
     * @param row
     * @param found
     */
    private void checkHourCap(Schedule.Row row, List<Violation> found) {
        int hours = row.hours();
        if (hours > DutyRules.HOUR_CAP) {
            found.add(Violation.by(Rule.HOUR_CAP_EXCEEDED, row.trooper().name(), null,
                    row.trooper().name() + " is down for " + hours + " hours; the cap is " + DutyRules.HOUR_CAP));
        }
    }
    
}
