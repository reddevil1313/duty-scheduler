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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backtracking search that fills every post on a sheet.
 *
 * <p>
 * One job: come back with a schedule that breaks no rule. It
 * does not care whether the result is a good schedule.
 * 
 * <p>
 * The search walks positions in
 * time order, and for each one tries every trooper who could legally take it,
 * recursing after each and undoing on the way back out.
 *
 * <p>
 * To keep the search from wandering into dead ends, it uses four
 * pruning techniques:
 *
 * <ul>
 * <li><b>Hour targets as a budget.</b> Each man arrives with an exact number of
 * hours to do. A position can only go to someone with hours left, so the
 * search cannot wander into schedules where one man does everything.
 * <li><b>The night/day partition.</b> The night group takes the silent hours
 * and nobody else can; everyone else takes the rest. This halves the
 * branching and, more importantly, is what stops a man being given 2200 and
 * 1800 on the same sheet.
 * <li><b>Symmetry breaking on PAC.</b> The two PAC positions at an hour are
 * interchangeable, so putting A on the first and B on the second is the
 * same schedule as the reverse. Only the ordered pair is explored.
 * <li><b>A capacity prune.</b> Before recursing, every man who still owes hours
 * must have somewhere left to do them. If not, the branch is abandoned.
 * </ul>
 */
public final class ScheduleSolver {

    /** How far a single candidate split may search before it is abandoned. */
    private static final long NODE_BUDGET = 200_000;

    /** One post at one hour. */
    private record Position(int slot, Post post, int copy) {
    }

    /**
     * Solves a sheet and returns the best arrangement it found.
     *
     * <p>
     * It tries every crossover from zero to the maximum that could be absorbed
     * by the crossover window, and returns the best schedule it finds.
     * If none of the crossovers yield a solution, it returns a failure.
     * The returned schedule is legal.
     */
    public SolveResult solve(SolveRequest request) {
        // A chosen BUS Trooper who cannot stand the BUS hours is refused before
        // any search: the answer would only ever be "no solution", reached the
        // slow way and explained worse.
        String busProblem = busProblem(request);
        if (busProblem != null) {
            return SolveResult.failed(busProblem, null, 0);
        }
        if (request.turnout() != null) {
            return solveFixed(request);
        }
        SolveResult best = null;
        ScheduleScore bestScore = null;
        String firstReason = null;
        long nodes = 0;

        for (int crossover : crossoverCandidates(request)) {
            SolveResult candidate = new Run(request, crossover).go();
            nodes += candidate.nodes();
            if (!candidate.isSolved()) {
                if (firstReason == null) {
                    firstReason = candidate.reason();
                }
                continue;
            }
            ScheduleScore score = ScheduleScore.of(candidate.schedule());
            if (score.isBetterThan(bestScore)) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best == null) {
            return SolveResult.failed(firstReason == null ? "No solution." : firstReason, null, nodes);
        }
        return new SolveResult(best.schedule(), best.targets(), null, nodes);
    }

    /**
     * Why the chosen BUS Trooper cannot take the BUS hours on this sheet, or
     * null when they can — or when nobody was chosen, or the sheet has no BUS.
     */
    static String busProblem(SolveRequest request) {
        Trooper bus = request.bus();
        if (bus == null) {
            return null;
        }
        DutyDay day = request.day();
        boolean[] free = Availability.mask(bus, day, request.absences());
        boolean onNight = request.nightGroup().contains(bus);
        boolean[] cross = HourTargets.crossoverWindow(day);
        for (int slot : busSlots(day)) {
            String at = day.slot(slot).label();
            if (!free[slot]) {
                return bus.name() + " cannot take BUS: they are not available at " + at
                        + (bus.stayOut() ? ", being a stay-out." : ", being on leave.");
            }
            if (onNight && !cross[slot]) {
                return bus.name() + " cannot take BUS: they are on night, and " + at
                        + " is outside the hours a night Trooper may cross into.";
            }
        }
        return null;
    }

    /** The slots BUS stands on this sheet, in order. Empty at the weekend. */
    static List<Integer> busSlots(DutyDay day) {
        DayDemand demand = DayDemand.of(day);
        List<Integer> out = new ArrayList<>();
        for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
            if (demand.at(slot).getOrDefault(Post.BUS, 0) > 0) {
                out.add(slot);
            }
        }
        return out;
    }

    /**
     * Fixed-length turnouts: everyone does the same hours and the MP team takes
     * whatever that leaves. The same search, the same rules and the same
     * validator — only the space narrows, because the hour targets and the
     * crossover are no longer choices but consequences of the turnout.
     *
     * <ul>
     * <li>Everyone does {@code N}, or as near as their own availability allows.
     * <li>The silent hours are always fully manned. If the night group at
     * {@code N} each falls short of them, night Troopers go to {@code N + 1},
     * fewest past hours first, until they do — and if even that falls short,
     * the turnout is refused.
     * <li>What the night group does beyond the silent hours spills into the
     * morning crossover, as it would in the free split.
     * <li>Every other hour with a post keeps at least one Trooper on; the rest
     * of the post-hours go to the MP team. A turnout that would leave more
     * hours than the sheet has to give is refused, not quietly shortened.
     * </ul>
     */
    private SolveResult solveFixed(SolveRequest request) {
        DutyDay day = request.day();
        int n = request.turnout();
        boolean[] silent = Availability.silentSlots(day);
        boolean[] daytime = Availability.daySlots(day);
        boolean[] cross = HourTargets.crossoverWindow(day);
        boolean[] nightWindow = silent.clone();
        for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
            nightWindow[slot] |= cross[slot];
        }
        int silentDemand = Availability.demandWithin(day, silent);
        int dayDemand = Availability.demandWithin(day, daytime);
        int crossCeiling = Run.crossoverCeiling(day);

        Map<Trooper, Integer> hours = new LinkedHashMap<>();
        Map<Trooper, Integer> room = new LinkedHashMap<>();
        for (Trooper t : request.nightGroup()) {
            int cap = Math.min(DutyRules.HOUR_CAP,
                    Availability.capacityWithin(Availability.mask(t, day, request.absences()), nightWindow));
            room.put(t, cap);
            hours.put(t, Math.min(n, cap));
        }
        int night = hours.values().stream().mapToInt(Integer::intValue).sum();
        // Short of the silent hours: an hour over the turnout, fewest past
        // hours first, as long as it takes and as far as each one has room.
        List<Trooper> byHistory = request.nightGroup().stream()
                .sorted(Comparator.comparingInt((Trooper t) -> request.history().getOrDefault(t, 0))
                        .thenComparing(Trooper::name))
                .toList();
        for (Trooper t : byHistory) {
            if (night >= silentDemand) {
                break;
            }
            if (hours.get(t) == n && room.get(t) > n) {
                hours.put(t, n + 1);
                night++;
            }
        }
        if (night < silentDemand) {
            return SolveResult.failed("The silent hours need " + silentDemand + " Trooper-hours, and the night group"
                    + " at " + n + "h, or " + (n + 1) + "h, each can give only " + night
                    + ". Add someone to the night group, or use the free split.", null, 0);
        }
        int crossover = night - silentDemand;
        if (crossover > crossCeiling) {
            return SolveResult.failed("At " + n + "h each the night group makes " + night + " hours, more than the"
                    + " silent hours and the morning after can take (" + (silentDemand + crossCeiling)
                    + "). Use the free split, or a shorter turnout.", null, 0);
        }

        int dayHours = 0;
        for (Trooper t : request.dayGroup()) {
            int cap = Math.min(DutyRules.HOUR_CAP,
                    Availability.capacityWithin(Availability.mask(t, day, request.absences()), daytime));
            int h = Math.min(n, cap);
            hours.put(t, h);
            dayHours += h;
        }
        if (dayHours > dayDemand - crossover) {
            return SolveResult.failed("At " + n + "h each the team makes " + (night + dayHours) + " hours, more than"
                    + " the " + (silentDemand + dayDemand) + " this sheet needs. Use the free split, or a shorter"
                    + " turnout.", null, 0);
        }

        HourTargets targets = new HourTargets(hours, silentDemand + crossover, dayHours);
        SolveResult result = new Run(request, crossover, targets).go();
        if (!result.isSolved()) {
            return result;
        }
        return new SolveResult(result.schedule(), result.targets(), null, result.nodes());
    }

    /**
     * How many day hours to consider handing to the night group.
     */
    private static List<Integer> crossoverCandidates(SolveRequest request) {
        List<Integer> out = new ArrayList<>();
        int ceiling = Run.crossoverCeiling(request.day());
        for (int x = 0; x <= ceiling; x++) {
            out.add(x);
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // One solve. Held in its own object so the solver itself stays stateless and
    // can be shared across threads.
    // -----------------------------------------------------------------------
    private static final class Run {

        private final SolveRequest request;
        private final int crossover;
        private final boolean[] crossSlots;
        private final DutyDay day;
        private final List<Trooper> troopers;
        private final HourTargets targets;

        private final boolean[] silent;
        private final boolean[][] available; // [trooper][slot]
        private final boolean[] onNight; // [trooper]
        private final Post[][] grid; // [trooper][slot], null = off
        private final int[] owed; // hours each man still has to do
        private final List<Position> positions;
        private final int[] filledBy; // [position] -> trooper index, -1 unset
        // Cells placed by a human choice before the search, which neither the
        // search nor the improvers may move. Today only the chosen BUS hours.
        private final boolean[][] pinned; // [trooper][slot]
        private final int busTrooper; // index of the chosen BUS Trooper, -1 for none
        // Fixed turnouts only: positions may be left to the MP team, never in
        // the silent hours and never the last chance to man an hour, and
        // exactly this many of them — what the turnouts do not reach.
        private final boolean fixed;
        private int mpBudget;
        private static final int TO_MP = -2; // filledBy marker: left to the MP team

        private long nodes;
        private boolean exhausted;

        Run(SolveRequest request, int crossover) {
            this(request, crossover, HourTargets.allocate(request.day(), request.nightGroup(), request.dayGroup(),
                    request.absences(), request.history(), crossover));
        }

        /** With targets already decided, as fixed turnouts decide them. */
        Run(SolveRequest request, int crossover, HourTargets targets) {
            this.request = request;
            this.crossover = crossover;
            this.day = request.day();
            this.crossSlots = HourTargets.crossoverWindow(request.day());
            this.troopers = request.roster();
            this.targets = targets;
            this.fixed = request.turnout() != null;

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
            this.pinned = new boolean[n][DutyDay.SLOT_COUNT];

            // The chosen BUS Trooper's hours are placed now and count toward
            // their target, exactly as if the search had chosen them.
            this.busTrooper = request.bus() == null ? -1 : troopers.indexOf(request.bus());
            if (busTrooper >= 0) {
                for (int i = 0; i < positions.size(); i++) {
                    Position position = positions.get(i);
                    if (position.post() == Post.BUS) {
                        filledBy[i] = busTrooper;
                        grid[busTrooper][position.slot()] = Post.BUS;
                        pinned[busTrooper][position.slot()] = true;
                        owed[busTrooper]--;
                    }
                }
            }
            this.mpBudget = fixed ? Math.max(0, positions.size() - targets.total()) : 0;
        }

        /** Every post-hour the sheet needs, in time order. */
        private static List<Position> buildPositions(DutyDay day) {
            DayDemand demand = DayDemand.of(day);
            List<Position> out = new ArrayList<>();
            for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
                // The demand map is a multiset: PAC=2, BUS=1, etc. Each copy is a separate
                // position.
                Map<Post, Integer> need = demand.at(slot);
                for (Post post : Post.values()) {
                    int count = need.getOrDefault(post, 0);
                    // Each copy of a post is a separate position, so PAC=2 means two positions at
                    // that hour.
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
            if (search(0)) {
                // Improve a legal schedule by swapping segments between rows to reduce wasted
                // span.
                Post[][] improved = new SpanImprover(troopers, this::eligible, pinned).improve(grid);
                // Then cut walks between AP and GG. Second, and on its own,
                // because it trades posts without moving hours: it cannot undo
                // anything the span pass just won.
                improved = new PostSwapImprover(pinned).improve(improved);
                for (int t = 0; t < troopers.size(); t++) {
                    grid[t] = improved[t];
                }
                return SolveResult.solved(toSchedule(), targets, nodes);
            }
            return SolveResult.failed(exhausted
                    ? "Gave up looking for an arrangement of this split."
                    : "No legal arrangement of these hours exists for this manpower.", targets, nodes);
        }

        /**
         * Cheap checks worth doing before searching at all.
         */
        private String obviouslyImpossible() {
            // Most specific first: a short night group and an over-cap sheet both
            // show up as "not enough hours" if you check the total before them,
            // and the general message is the least useful of the three.
            if (busTrooper >= 0) {
                String who = troopers.get(busTrooper).name();
                if (owed[busTrooper] < 0) {
                    return who + " is down for " + targets.forTrooper(troopers.get(busTrooper))
                            + " hours on this split, fewer than the BUS run alone.";
                }
                for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
                    if (pinned[busTrooper][slot] && !rightGroup(busTrooper, slot)) {
                        return who + " is on night, and can only take BUS when night hours cross into the morning.";
                    }
                }
            }
            int minNight = Availability.minimumNightGroup(day);
            if (request.nightGroup().size() < minNight) {
                return "The silent hours need at least " + minNight + " Troopers and only "
                        + request.nightGroup().size() + " are on night.";
            }
            if (!targets.withinCap()) {
                return "Covering every post would take more than " + DutyRules.HOUR_CAP
                        + " hours from at least one Trooper.";
            }
            // With fixed turnouts a shortfall is the MP team's, by design.
            if (!fixed && targets.total() < positions.size()) {
                return "Not enough available manpower: the sheet needs " + positions.size()
                        + " Trooper-hours and this roster can absorb " + targets.total() + ".";
            }
            if (fixed && targets.total() < mandatory(0)) {
                return "At " + request.turnout() + "h each there are " + targets.total() + " hours for "
                        + mandatory(0) + " that must have a Trooper on: every silent post-hour, and one post"
                        + " every other hour. Use a longer turnout, or the free split.";
            }
            if (targets.total() > positions.size()) {
                return "The hour targets over-subscribe the sheet.";
            }
            for (int t = 0; t < troopers.size(); t++) {
                if (owed[t] == 0) {
                    continue;
                }
                int room = capacityFrom(t, 0);
                if (room < owed[t]) {
                    return troopers.get(t).name() + " is down for " + owed[t]
                            + " hours but only has room for " + room + " on this sheet.";
                }
            }
            // The hour that usually makes a thin day impossible: 0700 stands two
            // PAC and the bus run, and a stay-out is not in yet.
            for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
                // Positions already pinned are covered, and the Trooper pinned
                // there is not free for another post at the same hour.
                int need = 0;
                boolean manned = false;
                for (int i = 0; i < positions.size(); i++) {
                    if (positions.get(i).slot() == slot) {
                        if (filledBy[i] < 0) {
                            need++;
                        } else {
                            manned = true;
                        }
                    }
                }
                // With fixed turnouts an hour outside the silent block only
                // needs one Trooper; the rest can be the MP team's.
                if (fixed && !silent[slot]) {
                    need = manned ? 0 : Math.min(need, 1);
                }
                if (need == 0) {
                    continue;
                }
                int bodies = 0;
                for (int t = 0; t < troopers.size(); t++) {
                    if (owed[t] > 0 && available[t][slot] && rightGroup(t, slot) && grid[t][slot] == null) {
                        bodies++;
                    }
                }
                if (bodies < need) {
                    return day.slot(slot).label() + " needs " + need + " Troopers and only "
                            + bodies + " can be on at that hour.";
                }
            }
            return null;
        }

        /**
         * Night men take the silent hours, day men take the rest — except that a
         * night man may also be given one of the first few day hours, which is
         * what "crossover" buys.
         */
        private boolean rightGroup(int trooper, int slot) {
            if (onNight[trooper]) {
                return silent[slot] || (crossover > 0 && crossSlots[slot]);
            }
            return !silent[slot];
        }

        /** The most day hours the crossover window could absorb. */
        static int crossoverCeiling(DutyDay day) {
            return Availability.demandWithin(day, HourTargets.crossoverWindow(day));
        }

        private boolean search(int index) {
            if (++nodes > NODE_BUDGET) {
                exhausted = true;
                return false;
            }
            if (index == positions.size()) {
                // Every position decided; with fixed turnouts that is only done
                // when everyone has done their hours, not just when the list ends.
                return !fixed || Arrays.stream(owed).allMatch(o -> o == 0);
            }
            if (filledBy[index] >= 0) {
                // Pinned before the search began; nothing to choose.
                return search(index + 1);
            }
            Position position = positions.get(index);
            int slot = position.slot();

            // The two PAC positions at one hour are interchangeable, so only ever
            // fill them in increasing trooper order. Without this the search
            // explores every arrangement twice. For the same reason, once the
            // first copy is left to the MP team the second goes the same way:
            // "first to MP, second filled" is the mirror of "first filled,
            // second to MP".
            boolean previousToMp = position.copy() > 0 && filledBy[index - 1] == TO_MP;
            int floor = position.copy() == 0 || previousToMp ? 0 : filledBy[index - 1] + 1;

            for (int t : previousToMp ? List.<Integer>of() : candidates(slot, position.post(), floor)) {
                grid[t][slot] = position.post();
                owed[t]--;
                filledBy[index] = t;
                // If the trooper can take this position and the rest of the sheet can still be
                // filled, recurse.
                if (capacityOk(slot) && search(index + 1)) {
                    return true;
                }

                // Undo and try the next candidate.
                grid[t][slot] = null;
                owed[t]++;
                filledBy[index] = -1;
            }
            // Fixed turnouts: hand this one to the MP team, if that is allowed
            // and the hours still owed can still man what must be manned.
            if (canLeaveToMp(index)) {
                filledBy[index] = TO_MP;
                mpBudget--;
                if (Arrays.stream(owed).sum() >= mandatory(index + 1) && search(index + 1)) {
                    return true;
                }
                mpBudget++;
                filledBy[index] = -1;
            }
            return false;
        }

        /**
         * Whether a position may go to the MP team: only with fixed turnouts,
         * while there is budget, never in the silent hours, and never as the last
         * chance to have someone from the team on at that hour.
         */
        private boolean canLeaveToMp(int index) {
            if (!fixed || mpBudget <= 0) {
                return false;
            }
            int slot = positions.get(index).slot();
            if (silent[slot]) {
                return false;
            }
            for (int i = 0; i < positions.size(); i++) {
                if (positions.get(i).slot() != slot || i == index) {
                    continue;
                }
                // Someone already on at this hour, or a later position here that
                // can still be filled: leaving this one does not empty the hour.
                if ((i < index && filledBy[i] >= 0) || i > index) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Positions from {@code from} on that must have a Trooper: every silent
         * one, and one per other hour not yet manned. A lower bound on the hours
         * the search still needs, used to prune.
         */
        private int mandatory(int from) {
            if (!fixed) {
                return 0;
            }
            int count = 0;
            boolean[] counted = new boolean[DutyDay.SLOT_COUNT];
            for (int i = 0; i < from && i < positions.size(); i++) {
                if (filledBy[i] >= 0) {
                    counted[positions.get(i).slot()] = true;
                }
            }
            for (int i = from; i < positions.size(); i++) {
                int slot = positions.get(i).slot();
                if (filledBy[i] >= 0) {
                    counted[slot] = true; // pinned ahead: already manned
                    continue;
                }
                if (silent[slot]) {
                    count++;
                } else if (!counted[slot]) {
                    counted[slot] = true;
                    count++;
                }
            }
            return count;
        }

        /**
         * Who could take this position, most-committed first. Trying the man with
         * the most hours still owed puts the tightest constraint at the top of the
         * tree, where failing is cheap.
         * 
         * @param floor The lowest trooper index to consider, for symmetry breaking on
         *              PAC.
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
            // Positions are filled in time order, so past this slot only pinned
            // cells are set. The run is counted both ways so a placement that
            // runs into a pinned BUS is judged as the stint it makes.
            int run = 1;
            for (int k = slot - 1; k >= 0 && grid[t][k] != null; k--) {
                run++;
            }
            for (int k = slot + 1; k < DutyDay.SLOT_COUNT && grid[t][k] != null; k++) {
                run++;
            }

            // The stint length rule is a hard limit, so if the run is already too long
            // this branch is dead.
            if (run > DutyRules.MAX_STINT) {
                return false;
            }
            // The "free after" rule is a hard limit, in both directions: the post
            // before this one, and a pinned post straight after it.
            if (slot > 0 && grid[t][slot - 1] != null && !DutyRules.followsWithoutBreak(grid[t][slot - 1], post)) {
                return false;
            }
            if (slot + 1 < DutyDay.SLOT_COUNT && grid[t][slot + 1] != null
                    && !DutyRules.followsWithoutBreak(post, grid[t][slot + 1])) {
                return false;
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

        /** Availability and group in one question, for the improver. */
        private boolean eligible(int trooper, int slot) {
            return available[trooper][slot] && rightGroup(trooper, slot);
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
