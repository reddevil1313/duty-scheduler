package com.dutyscheduler.duty.solver;

import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.rules.HourTargets;

import java.time.LocalDate;
import java.util.Optional;

/**
 * What the search came back with.
 *
 * A failure carries a reason rather than an empty result.
 *
 * @param reusedFrom the date of the logged sheet laid onto today's Troopers
 *                   instead of solving, or null when the sheet was solved
 */
public record SolveResult(Schedule schedule, HourTargets targets, String reason, long nodes, LocalDate reusedFrom) {

    public SolveResult(Schedule schedule, HourTargets targets, String reason, long nodes) {
        this(schedule, targets, reason, nodes, null);
    }

    public static SolveResult solved(Schedule schedule, HourTargets targets, long nodes) {
        return new SolveResult(schedule, targets, null, nodes);
    }

    public static SolveResult failed(String reason, HourTargets targets, long nodes) {
        return new SolveResult(null, targets, reason, nodes);
    }

    public static SolveResult reused(Schedule schedule, LocalDate from) {
        return new SolveResult(schedule, null, null, 0, from);
    }

    public boolean isSolved() {
        return schedule != null;
    }

    public Optional<Schedule> maybeSchedule() {
        return Optional.ofNullable(schedule);
    }
}
