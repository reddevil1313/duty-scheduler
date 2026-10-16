package com.dutyscheduler.duty.solver;

import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Trooper;

import java.util.List;
import java.util.Map;

/**
 * A request to the solver, containing all the information it needs to produce a schedule.
 *
 * @param bus the Trooper chosen to take the BUS hours, or null to let the
 *            solver choose. Pinned before the search starts, the way the night
 *            group is: a human choice the search works around, not a
 *            preference it may trade away.
 */
public record SolveRequest(DutyDay day,
                           List<Trooper> roster,
                           List<Trooper> nightGroup,
                           List<Absence> absences,
                           Map<Trooper, Integer> history,
                           Trooper bus) {

    public SolveRequest {
        roster = List.copyOf(roster);
        nightGroup = List.copyOf(nightGroup);
        absences = List.copyOf(absences);
        history = Map.copyOf(history);
        if (!roster.containsAll(nightGroup)) {
            throw new IllegalArgumentException("The night group must come from the roster.");
        }
        if (bus != null && !roster.contains(bus)) {
            throw new IllegalArgumentException("The BUS Trooper must come from the roster.");
        }
    }

    /** Without a BUS choice: the solver picks who takes it. */
    public SolveRequest(DutyDay day, List<Trooper> roster, List<Trooper> nightGroup,
            List<Absence> absences, Map<Trooper, Integer> history) {
        this(day, roster, nightGroup, absences, history, null);
    }

    public static SolveRequest of(DutyDay day, List<Trooper> roster, List<Trooper> nightGroup) {
        return new SolveRequest(day, roster, nightGroup, List.of(), Map.of(), null);
    }

    public SolveRequest withAbsences(List<Absence> absences) {
        return new SolveRequest(day, roster, nightGroup, absences, history, bus);
    }

    public SolveRequest withHistory(Map<Trooper, Integer> history) {
        return new SolveRequest(day, roster, nightGroup, absences, history, bus);
    }

    public SolveRequest withBus(Trooper bus) {
        return new SolveRequest(day, roster, nightGroup, absences, history, bus);
    }

    /** Everyone who is not on the silent hours. */
    public List<Trooper> dayGroup() {
        return roster.stream().filter(t -> !nightGroup.contains(t)).toList();
    }
}
