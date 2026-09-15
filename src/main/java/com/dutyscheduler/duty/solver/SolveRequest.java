package com.dutyscheduler.duty.solver;

import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Trooper;

import java.util.List;
import java.util.Map;

/**
 * A request to the solver, containing all the information it needs to produce a schedule.
 *
 */
public record SolveRequest(DutyDay day,
                           List<Trooper> roster,
                           List<Trooper> nightGroup,
                           List<Absence> absences,
                           Map<Trooper, Integer> history) {

    public SolveRequest {
        roster = List.copyOf(roster);
        nightGroup = List.copyOf(nightGroup);
        absences = List.copyOf(absences);
        history = Map.copyOf(history);
        if (!roster.containsAll(nightGroup)) {
            throw new IllegalArgumentException("The night group must come from the roster.");
        }
    }

    public static SolveRequest of(DutyDay day, List<Trooper> roster, List<Trooper> nightGroup) {
        return new SolveRequest(day, roster, nightGroup, List.of(), Map.of());
    }

    public SolveRequest withAbsences(List<Absence> absences) {
        return new SolveRequest(day, roster, nightGroup, absences, history);
    }

    public SolveRequest withHistory(Map<Trooper, Integer> history) {
        return new SolveRequest(day, roster, nightGroup, absences, history);
    }

    /** Everyone who is not on the silent hours. */
    public List<Trooper> dayGroup() {
        return roster.stream().filter(t -> !nightGroup.contains(t)).toList();
    }
}
