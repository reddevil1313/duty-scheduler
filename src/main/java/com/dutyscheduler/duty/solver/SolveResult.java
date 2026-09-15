package com.dutyscheduler.duty.solver;

import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.rules.HourTargets;

import java.util.Optional;

/**
 * What the search came back with.
 *
 * A failure carries a reason rather than an empty result.
 */
public record SolveResult(Schedule schedule, HourTargets targets, String reason, long nodes) {

    public static SolveResult solved(Schedule schedule, HourTargets targets, long nodes) {
        return new SolveResult(schedule, targets, null, nodes);
    }

    public static SolveResult failed(String reason, HourTargets targets, long nodes) {
        return new SolveResult(null, targets, reason, nodes);
    }

    public boolean isSolved() {
        return schedule != null;
    }

    public Optional<Schedule> maybeSchedule() {
        return Optional.ofNullable(schedule);
    }
}
