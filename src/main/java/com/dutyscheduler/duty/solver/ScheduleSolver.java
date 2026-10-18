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
 * Backtracking search that fills every post on a sheet with a legal schedule.
 * Positions are walked in time order; each tries every Trooper who could take it.
 *
 * <p>
 * Pruning:
 * <ul>
 * <li>Hour targets: a position only goes to someone with hours left.
 * <li>Night/day split: only the night group takes silent hours, so nobody gets
 * both 2200 and 1800.
 * <li>The two PAC positions at an hour are filled in trooper order only.
 * <li>Anyone still owing hours must have room left to do them.
 * </ul>
 */
public final class ScheduleSolver {

    /** How far a single candidate split may search before it is abandoned. */
    private static final long NODE_BUDGET = 200_000;

    /** One post at one hour. */
    private record Position(int slot, Post post, int copy) {
    }

    /**
     * Tries every crossover from zero up to what the crossover window can take,
     * and returns the best scoring schedule.
     */
    public SolveResult solve(SolveRequest request) {
        // Searching would only end in "no solution", with a worse message.
        String busProblem = busProblem(request);
        if (busProblem != null) {
            return SolveResult.failed(busProblem, null, 0);
        }
        // Reuse the latest logged sheet that fits today's Troopers.
        for (Schedule logged : request.reusable()) {
            var laid = SheetReuse.layOnto(request, logged);
            if (laid.isPresent()) {
                return SolveResult.reused(laid.get(), logged.day().date());
            }
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

    /** Why the chosen BUS Trooper can't take the BUS hours, or null if they can. */
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
     * Fixed turnouts: everyone does N hours (or what their availability allows)
     * and the MP team takes the rest. Targets and crossover follow from N.
     *
     * <ul>
     * <li>Silent hours are always fully manned. If the night group falls short,
     * night Troopers go to N + 1, fewest past hours first; if still short, refuse.
     * <li>Night hours past the silent block become the crossover.
     * <li>Every other hour keeps at least one Trooper on.
     * <li>A turnout giving more hours than the sheet needs is refused.
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
        // Short of the silent hours: give N + 1, fewest past hours first.
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
                    + ". Add someone to the night group, or use full coverage.", null, 0);
        }
        int crossover = night - silentDemand;
        if (crossover > crossCeiling) {
            return SolveResult.failed("At " + n + "h each the night group makes " + night + " hours, more than the"
                    + " silent hours and the morning after can take (" + (silentDemand + crossCeiling)
                    + "). Use full coverage, or fewer hours each.", null, 0);
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
                    + " the " + (silentDemand + dayDemand) + " this sheet needs. Use full coverage, or fewer"
                    + " hours each.", null, 0);
        }

        HourTargets targets = new HourTargets(hours, silentDemand + crossover, dayHours);
        SolveResult result = new Run(request, crossover, targets).go();
        if (!result.isSolved()) {
            return result;
        }
        return new SolveResult(result.schedule(), result.targets(), null, result.nodes());
    }

    /** How many day hours to consider handing to the night group. */
    private static List<Integer> crossoverCandidates(SolveRequest request) {
        List<Integer> out = new ArrayList<>();
        int ceiling = Run.crossoverCeiling(request.day());
        for (int x = 0; x <= ceiling; x++) {
            out.add(x);
        }
        return out;
    }

    // State for one solve, so the solver itself stays stateless and thread-safe.
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
        private final int[] owed; // hours each Trooper still has to do
        private final List<Position> positions;
        private final int[] filledBy; // [position] -> trooper index, -1 unset
        // Cells fixed before the search (the chosen BUS hours); nothing may move them.
        private final boolean[][] pinned; // [trooper][slot]
        private final int busTrooper; // index of the chosen BUS Trooper, -1 for none
        // Fixed turnouts: how many positions may still go to the MP team.
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

            // Place the chosen BUS hours now; they count toward the target.
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
                // PAC=2 means two positions at that hour.
                Map<Post, Integer> need = demand.at(slot);
                for (Post post : Post.values()) {
                    int count = need.getOrDefault(post, 0);
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
                Post[][] improved = new SpanImprover(troopers, this::eligible, pinned).improve(grid);
                // Runs second: it only trades posts, so it can't undo the span gains.
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

        /** Cheap checks before searching. Most specific message first. */
        private String obviouslyImpossible() {
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
            // With fixed turnouts the MP team covers the shortfall.
            if (!fixed && targets.total() < positions.size()) {
                return "Not enough available manpower: the sheet needs " + positions.size()
                        + " Trooper-hours and this roster can absorb " + targets.total() + ".";
            }
            if (fixed && targets.total() < mandatory(0)) {
                return "At " + request.turnout() + "h each there are " + targets.total() + " hours for "
                        + mandatory(0) + " that must have a Trooper on: every silent post-hour, and one post"
                        + " every other hour. Use more hours each, or full coverage.";
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
            // Enough people each hour? Usually 0700 fails: two PAC and BUS, and
            // stay-outs are not in yet. Pinned positions are already covered.
            for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
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
                // Fixed turnouts: outside silent hours, one Trooper is enough.
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

        /** Night group: silent hours, plus the crossover window if any. Day group: the rest. */
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
                // Fixed turnouts also need everyone's hours used up.
                return !fixed || Arrays.stream(owed).allMatch(o -> o == 0);
            }
            if (filledBy[index] >= 0) {
                return search(index + 1); // pinned
            }
            Position position = positions.get(index);
            int slot = position.slot();

            // PAC copies are interchangeable: fill them in increasing trooper
            // order, and if the first went to MP the second does too.
            boolean previousToMp = position.copy() > 0 && filledBy[index - 1] == TO_MP;
            int floor = position.copy() == 0 || previousToMp ? 0 : filledBy[index - 1] + 1;

            for (int t : previousToMp ? List.<Integer>of() : candidates(slot, position.post(), floor)) {
                grid[t][slot] = position.post();
                owed[t]--;
                filledBy[index] = t;
                if (capacityOk(slot) && search(index + 1)) {
                    return true;
                }
                grid[t][slot] = null;
                owed[t]++;
                filledBy[index] = -1;
            }
            // Fixed turnouts: try leaving it to the MP team.
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
         * Fixed turnouts with budget left, not a silent hour, and not the last
         * chance to have someone on at that hour.
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
                // Someone already on, or a later position here still open.
                if ((i < index && filledBy[i] >= 0) || i > index) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Lower bound on hours still needed from {@code from}: every silent
         * position, plus one per other hour not yet manned.
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
                    counted[slot] = true; // pinned
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
         * Who could take this position, most hours owed first so the tightest
         * choice fails early.
         *
         * @param floor lowest trooper index to consider (PAC symmetry)
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

        private boolean canPlace(int t, int slot, Post post) {
            if (owed[t] <= 0 || !available[t][slot] || grid[t][slot] != null) {
                return false;
            }
            if (!rightGroup(t, slot)) {
                return false;
            }
            // Count both ways: later cells can already be set if pinned (BUS).
            int run = 1;
            for (int k = slot - 1; k >= 0 && grid[t][k] != null; k--) {
                run++;
            }
            for (int k = slot + 1; k < DutyDay.SLOT_COUNT && grid[t][k] != null; k++) {
                run++;
            }
            if (run > DutyRules.MAX_STINT) {
                return false;
            }
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
         * Upper bound on the hours a Trooper can still take from {@code frontier}.
         * Must never under-estimate, or a real schedule gets pruned.
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

        private boolean eligible(int trooper, int slot) {
            return available[trooper][slot] && rightGroup(trooper, slot);
        }

        private Schedule toSchedule() {
            Schedule.Builder builder = Schedule.builder(day);
            for (int t = 0; t < troopers.size(); t++) {
                builder.row(troopers.get(t), Arrays.asList(grid[t]));
            }
            return builder.build();
        }
    }
}
