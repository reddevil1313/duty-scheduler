package com.dutyscheduler.duty.solver;

import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;

import java.util.List;
import java.util.Map;

/**
 * A request to the solver, containing all the information it needs to produce a schedule.
 *
 * @param bus      the Trooper chosen to take the BUS hours, or null to let the
 *                 solver choose. Pinned before the search starts, the way the
 *                 night group is: a human choice the search works around, not a
 *                 preference it may trade away.
 * @param turnout  null for the free split; otherwise everyone's hours, 5 to 7,
 *                 with what that leaves uncovered handed to the MP team. See
 *                 {@link ScheduleSolver} for the rules it keeps.
 * @param reusable logged sheets with the same posts, headcount and turnout,
 *                 latest first. The first that can be laid onto today's
 *                 Troopers is used as it is, instead of solving; see
 *                 {@link SheetReuse}.
 */
public record SolveRequest(DutyDay day,
                           List<Trooper> roster,
                           List<Trooper> nightGroup,
                           List<Absence> absences,
                           Map<Trooper, Integer> history,
                           Trooper bus,
                           Integer turnout,
                           List<Schedule> reusable) {

    public static final int MIN_TURNOUT = 5;
    public static final int MAX_TURNOUT = 7;


    public SolveRequest {
        roster = List.copyOf(roster);
        nightGroup = List.copyOf(nightGroup);
        absences = List.copyOf(absences);
        history = Map.copyOf(history);
        reusable = reusable == null ? List.of() : List.copyOf(reusable);
        if (!roster.containsAll(nightGroup)) {
            throw new IllegalArgumentException("The night group must come from the roster.");
        }
        if (bus != null && !roster.contains(bus)) {
            throw new IllegalArgumentException("The BUS Trooper must come from the roster.");
        }
        if (turnout != null && (turnout < MIN_TURNOUT || turnout > MAX_TURNOUT)) {
            throw new IllegalArgumentException(
                    "A fixed turnout is " + MIN_TURNOUT + " to " + MAX_TURNOUT + " hours, not " + turnout + ".");
        }
    }

    /** Without a BUS choice or a fixed turnout: the free split. */
    public SolveRequest(DutyDay day, List<Trooper> roster, List<Trooper> nightGroup,
            List<Absence> absences, Map<Trooper, Integer> history) {
        this(day, roster, nightGroup, absences, history, null, null, List.of());
    }

    /** The free split, with an optional BUS choice. */
    public SolveRequest(DutyDay day, List<Trooper> roster, List<Trooper> nightGroup,
            List<Absence> absences, Map<Trooper, Integer> history, Trooper bus) {
        this(day, roster, nightGroup, absences, history, bus, null, List.of());
    }

    public static SolveRequest of(DutyDay day, List<Trooper> roster, List<Trooper> nightGroup) {
        return new SolveRequest(day, roster, nightGroup, List.of(), Map.of(), null, null, List.of());
    }

    public SolveRequest withAbsences(List<Absence> absences) {
        return new SolveRequest(day, roster, nightGroup, absences, history, bus, turnout, reusable);
    }

    public SolveRequest withHistory(Map<Trooper, Integer> history) {
        return new SolveRequest(day, roster, nightGroup, absences, history, bus, turnout, reusable);
    }

    public SolveRequest withBus(Trooper bus) {
        return new SolveRequest(day, roster, nightGroup, absences, history, bus, turnout, reusable);
    }

    public SolveRequest withTurnout(Integer turnout) {
        return new SolveRequest(day, roster, nightGroup, absences, history, bus, turnout, reusable);
    }

    public SolveRequest withReusable(List<Schedule> reusable) {
        return new SolveRequest(day, roster, nightGroup, absences, history, bus, turnout, reusable);
    }

    /** Everyone who is not on the silent hours. */
    public List<Trooper> dayGroup() {
        return roster.stream().filter(t -> !nightGroup.contains(t)).toList();
    }
}
