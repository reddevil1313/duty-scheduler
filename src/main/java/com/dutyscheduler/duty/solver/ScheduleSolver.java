package com.dutyscheduler.duty.solver;

import com.dutyscheduler.duty.domain.DayDemand;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.DutyRules;
import com.dutyscheduler.duty.domain.Post;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;
import com.dutyscheduler.duty.rules.Availability;
import com.dutyscheduler.duty.rules.HourTargets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Backtracking search that fills every post on a sheet.
 *
 * <p>One job: come back with a schedule that breaks no rule. It
 * does not care whether the result is a good schedule.
 * 
 * <p> The search walks positions in
 * time order, and for each one tries every trooper who could legally take it,
 * recursing after each and undoing on the way back out.
 *
 * <p>To keep the search from wandering into dead ends, it uses four
 * pruning techniques:
 *
 * <ul>
 *   <li><b>Hour targets as a budget.</b> Each man arrives with an exact number of
 *       hours to do. A position can only go to someone with hours left, so the
 *       search cannot wander into schedules where one man does everything.
 *   <li><b>The night/day partition.</b> The night group takes the silent hours
 *       and nobody else can; everyone else takes the rest. This halves the
 *       branching and, more importantly, is what stops a man being given 2200 and
 *       1800 on the same sheet.
 *   <li><b>Symmetry breaking on PAC.</b> The two PAC positions at an hour are
 *       interchangeable, so putting A on the first and B on the second is the
 *       same schedule as the reverse. Only the ordered pair is explored.
 *   <li><b>A capacity prune.</b> Before recursing, every man who still owes hours
 *       must have somewhere left to do them. If not, the branch is abandoned.
 * </ul>
 */
public final class ScheduleSolver {

    /** One post at one hour. */
    private record Position(int slot, Post post, int copy) {
    }

    public SolveResult solve(SolveRequest request) {
        return new Run(request).go();
    }

    // -----------------------------------------------------------------------
    // One solve. Held in its own object so the solver itself stays stateless and
    // can be shared across threads.
    // -----------------------------------------------------------------------
    private static final class Run {

        private final SolveRequest request;
        private final DutyDay day;
        private final List<Trooper> troopers;
        private final HourTargets targets;

        private final boolean[] silent;
        private final boolean[][] available;    // [trooper][slot]
        private final boolean[] onNight;        // [trooper]
        private final Post[][] grid;            // [trooper][slot], null = off
        private final int[] owed;               // hours each man still has to do
        private final List<Position> positions;
        private final int[] filledBy;           // [position] -> trooper index, -1 unset

        private long nodes;

        Run(SolveRequest request) {
            this.request = request;
            this.day = request.day();
            this.troopers = request.roster();
            this.targets = HourTargets.allocate(day, request.nightGroup(), request.dayGroup(),
                    request.absences(), request.history());

            int n = troopers.size();
            this.silent = Availability.silentSlots(day);
            this.available = new boolean[n][];
            this.onNight = new boolean[n];
            this.grid = new Post[n][DutyDay.SLOT_COUNT];
            this.owed = new int[n];
            for (int t = 0; t < n; t++) {
                Trooper trooper = troopers.get(t);
                available[t] = Availability.mask(trooper, day, request.absences());
                onNight[t] = request.nightGroup().contains(trooper);
                owed[t] = targets.forTrooper(trooper);
            }
            this.positions = buildPositions(day);
            this.filledBy = new int[positions.size()];
            Arrays.fill(filledBy, -1);
        }

        /** Every post-hour the sheet needs, in time order. */
        private static List<Position> buildPositions(DutyDay day) {
            DayDemand demand = DayDemand.of(day);
            List<Position> out = new ArrayList<>();
            for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
                // The demand map is a multiset: PAC=2, BUS=1, etc. Each copy is a separate position.
                Map<Post, Integer> need = demand.at(slot);
                for (Post post : Post.values()) {
                    int count = need.getOrDefault(post, 0);
                    // Each copy of a post is a separate position, so PAC=2 means two positions at that hour.
                    for (int copy = 0; copy < count; copy++) {
                        out.add(new Position(slot, post, copy));
                    }
                }
            }
            return List.copyOf(out);
        }

        SolveResult go() {
            String impossible = obviouslyImpossible();
            if (impossible != null) {
                return SolveResult.failed(impossible, targets, 0);
            }
            if (search(0)) { // found a legal arrangement of hours
                return SolveResult.solved(toSchedule(), targets, nodes);
            }
            return SolveResult.failed(
                    "No legal arrangement of these hours exists for this manpower.", targets, nodes);
        }

        /**
         * Cheap checks worth doing before searching at all.
         */
        private String obviouslyImpossible() {
            // Most specific first: a short night group and an over-cap sheet both
            // show up as "not enough hours" if you check the total before them,
            // and the general message is the least useful of the three.
            int minNight = Availability.minimumNightGroup(day);
            if (request.nightGroup().size() < minNight) {
                return "The silent hours need at least " + minNight + " STs and only "
                        + request.nightGroup().size() + " are on night.";
            }
            if (!targets.withinCap()) {
                return "Covering every post would take more than " + DutyRules.HOUR_CAP
                        + " hours from at least one ST.";
            }
            if (targets.total() < positions.size()) {
                return "Not enough available manpower: the sheet needs " + positions.size()
                        + " ST-hours and this roster can absorb " + targets.total() + ".";
            }
            if (targets.total() > positions.size()) {
                return "The hour targets over-subscribe the sheet.";
            }
            for (int t = 0; t < troopers.size(); t++) {
                if (owed[t] == 0) {
                    continue;
                }
                /*
                 * Check if the trooper has enough room for the required hours.
                 */
                int room = capacityFrom(t, 0);
                if (room < owed[t]) {
                    return troopers.get(t).name() + " is down for " + owed[t]
                            + " hours but only has room for " + room + " on this sheet.";
                }
            }
            // The hour that usually makes a thin day impossible: 0700 stands two
            // PAC and the bus run, and a stay-out is not in yet.
            for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
                final int hour = slot;
                int need = (int) positions.stream().filter(p -> p.slot() == hour).count();
                if (need == 0) {
                    continue;
                }
                int bodies = 0;
                for (int t = 0; t < troopers.size(); t++) {
                    // Possible Fault -> Night Group should be available for earlier day slots.
                    if (owed[t] > 0 && available[t][slot] && rightGroup(t, slot)) {
                        bodies++;
                    }
                }
                if (bodies < need) {
                    return day.slot(slot).label() + " needs " + need + " STs and only "
                            + bodies + " can be on at that hour.";
                }
            }
            return null;
        }

        private boolean rightGroup(int trooper, int slot) {
            return onNight[trooper] == silent[slot];
        }

        private boolean search(int index) {
            nodes++;
            if (index == positions.size()) {
                return true;
            }
            Position position = positions.get(index);
            int slot = position.slot();

            // The two PAC positions at one hour are interchangeable, so only ever
            // fill them in increasing trooper order. Without this the search
            // explores every arrangement twice.
            int floor = position.copy() == 0 ? 0 : filledBy[index - 1] + 1;

            for (int t : candidates(slot, position.post(), floor)) {
                grid[t][slot] = position.post();
                owed[t]--;
                filledBy[index] = t;
                // If the trooper can take this position and the rest of the sheet can still be filled, recurse.
                if (capacityOk(slot) && search(index + 1)) {
                    return true;
                }

                // Undo and try the next candidate.
                grid[t][slot] = null;
                owed[t]++;
                filledBy[index] = -1;
            }
            return false;
        }

        /**
         * Who could take this position, most-committed first. Trying the man with
         * the most hours still owed puts the tightest constraint at the top of the
         * tree, where failing is cheap.
         * 
         * @param floor The lowest trooper index to consider, for symmetry breaking on PAC.
         */
        private List<Integer> candidates(int slot, Post post, int floor) {
            List<Integer> out = new ArrayList<>();
            for (int t = floor; t < troopers.size(); t++) {
                if (canPlace(t, slot, post)) {
                    out.add(t);
                }
            }
            out.sort(Comparator.<Integer>comparingInt(t -> -owed[t]).thenComparingInt(t -> t));
            return out;
        }

        /**
         * Can the given trooper take this position at this slot?
         */
        private boolean canPlace(int t, int slot, Post post) {
            if (owed[t] <= 0 || !available[t][slot] || grid[t][slot] != null) {
                return false;
            }

            if (!rightGroup(t, slot)) {
                return false;
            }
            // Positions are filled in time order, so nothing past this slot is set
            // and only the run behind us can be broken.
            int run = 1;
            for (int k = slot - 1; k >= 0 && grid[t][k] != null; k--) {
                run++;
            }

            // The stint length rule is a hard limit, so if the run is already too long
            // this branch is dead.
            if (run > DutyRules.MAX_STINT) {
                return false;
            }

            if (slot > 0 && grid[t][slot - 1] != null) {
                Post previous = grid[t][slot - 1];
                // The "free after" rule is a hard limit, so if the previous post forbids this one

                if (previous != post
                        && !DutyRules.FREE_AFTER.getOrDefault(previous, Set.of()).contains(post)) {
                    return false;
                }
            }
            return true;
        }

        /** Has everyone who still owes hours got room left to do them? */
        private boolean capacityOk(int frontier) {
            for (int t = 0; t < troopers.size(); t++) {
                if (owed[t] > 0 && capacityFrom(t, frontier) < owed[t]) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Upper bound on the hours a man can still take from {@code frontier} on.
         * Over-estimating only costs a branch that turns out to be a dead end;
         * under-estimating would cut off a schedule that exists.
         */
        private int capacityFrom(int t, int frontier) {
            int run = 0;
            int total = 0;
            for (int slot = frontier; slot < DutyDay.SLOT_COUNT; slot++) {
                boolean usable = available[t][slot] && grid[t][slot] == null && rightGroup(t, slot);
                if (usable) {
                    run++;
                } else {
                    total += DutyRules.stintCapacity(run);
                    run = 0;
                }
            }
            return total + DutyRules.stintCapacity(run);
        }

        /** Generate a copyof the schedule with the hours filled in. */
        private Schedule toSchedule() {
            Schedule.Builder builder = Schedule.builder(day);
            for (int t = 0; t < troopers.size(); t++) {
                builder.row(troopers.get(t), Arrays.asList(grid[t]));
            }
            return builder.build();
        }
    }
}
