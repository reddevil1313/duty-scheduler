package com.dutyscheduler.duty.solver;

import com.dutyscheduler.duty.domain.DayDemand;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Post;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;
import com.dutyscheduler.duty.rules.Availability;
import com.dutyscheduler.duty.rules.HourTargets;
import com.dutyscheduler.duty.rules.Rule;
import com.dutyscheduler.duty.rules.ScheduleValidator;
import com.dutyscheduler.duty.rules.ValidationResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Lays a logged sheet onto today's Troopers, so a day that has been worked
 * before is worked the same way again instead of solved afresh.
 *
 * <p>
 * Its rows are the old sheet's turnouts, kept exactly; only who stands each
 * one changes. The same people are not required — the same headcount is — so
 * each row goes to anyone today who can stand it:
 * <ul>
 * <li>available for every hour of it, which rules out leave, a roster tick and
 * a stay-out's hours outside their window;
 * <li>on night if it has silent hours, and if on night, holding nothing beyond
 * the silent hours and the morning crossover;
 * <li>the chosen BUS Trooper, if there is one, takes the row with the BUS run.
 * </ul>
 * Where rows differ in length, the longer ones go to whoever has done fewest
 * hours so far, the same tie-break the solver's hour targets use.
 *
 * <p>
 * The result is then put to the validator like any sheet. Anything it cannot
 * lay, or that comes out breaking a rule, is not reused: the caller solves.
 */
final class SheetReuse {

    private SheetReuse() {
    }

    static Optional<Schedule> layOnto(SolveRequest request, Schedule logged) {
        DutyDay day = request.day();
        List<List<Post>> rows = logged.rows().stream()
                .filter(r -> r.hours() > 0)
                .map(Schedule.Row::posts)
                .sorted(Comparator.comparingInt((List<Post> r) -> -(int) r.stream().filter(p -> p != null).count()))
                .toList();

        List<Trooper> available = request.roster().stream()
                .filter(t -> anyTrue(Availability.mask(t, day, request.absences())))
                .sorted(Comparator.comparingInt((Trooper t) -> request.history().getOrDefault(t, 0))
                        .thenComparing(Trooper::name))
                .toList();
        if (available.size() != rows.size()) {
            return Optional.empty();
        }

        boolean[] silent = Availability.silentSlots(day);
        boolean[] cross = HourTargets.crossoverWindow(day);
        int n = available.size();
        boolean[][] fits = new boolean[n][n]; // [trooper][row]
        for (int t = 0; t < n; t++) {
            Trooper trooper = available.get(t);
            boolean[] free = Availability.mask(trooper, day, request.absences());
            boolean night = request.nightGroup().contains(trooper);
            for (int r = 0; r < n; r++) {
                fits[t][r] = canStand(rows.get(r), free, night, silent, cross)
                        && busAllows(request.bus(), trooper, rows.get(r));
            }
        }

        int[] rowOf = new int[n];
        boolean[] taken = new boolean[n];
        if (!assign(0, fits, rowOf, taken)) {
            return Optional.empty();
        }

        Schedule.Builder builder = Schedule.builder(day);
        for (Trooper trooper : request.roster()) {
            int t = available.indexOf(trooper);
            if (t < 0) {
                builder.empty(trooper);
            } else {
                builder.row(trooper, rows.get(rowOf[t]));
            }
        }
        Schedule sheet = builder.build();
        return keepsTheRules(sheet, request.turnout() != null) ? Optional.of(sheet) : Optional.empty();
    }

    /** Troopers in history order, each taking the first row they can stand; backtracks when stuck. */
    private static boolean assign(int t, boolean[][] fits, int[] rowOf, boolean[] taken) {
        if (t == fits.length) {
            return true;
        }
        for (int r = 0; r < fits.length; r++) {
            if (!taken[r] && fits[t][r]) {
                taken[r] = true;
                rowOf[t] = r;
                if (assign(t + 1, fits, rowOf, taken)) {
                    return true;
                }
                taken[r] = false;
            }
        }
        return false;
    }

    private static boolean canStand(List<Post> row, boolean[] free, boolean night, boolean[] silent, boolean[] cross) {
        for (int slot = 0; slot < row.size(); slot++) {
            if (row.get(slot) == null) {
                continue;
            }
            if (!free[slot]) {
                return false;
            }
            if (silent[slot] && !night) {
                return false;
            }
            if (night && !silent[slot] && !cross[slot]) {
                return false;
            }
        }
        return true;
    }

    /** With a BUS Trooper chosen, the BUS run is theirs: no BUS for anyone else, and theirs must have it. */
    private static boolean busAllows(Trooper bus, Trooper trooper, List<Post> row) {
        if (bus == null) {
            return true;
        }
        boolean hasBus = row.contains(Post.BUS);
        return trooper.equals(bus) ? hasBus : !hasBus;
    }

    /**
     * The validator's verdict, as the turnout reads it: a free-split sheet must
     * be clean; a fixed-turnout sheet may only be short where the MP team is
     * allowed to be — never a silent hour, never an hour with nobody on.
     */
    private static boolean keepsTheRules(Schedule sheet, boolean fixed) {
        ValidationResult result = new ScheduleValidator().validate(sheet);
        if (!fixed) {
            return result.ok();
        }
        if (!result.violations().stream().allMatch(v -> v.rule() == Rule.UNDER_MANNED)) {
            return false;
        }
        DutyDay day = sheet.day();
        boolean[] silent = Availability.silentSlots(day);
        DayDemand demand = DayDemand.of(day);
        for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
            final int s = slot;
            int on = (int) sheet.rows().stream().filter(r -> r.at(s) != null).count();
            int need = demand.headcountAt(slot);
            if (silent[slot] ? on < need : (need > 0 && on == 0)) {
                return false;
            }
        }
        return true;
    }

    private static boolean anyTrue(boolean[] mask) {
        for (boolean b : mask) {
            if (b) {
                return true;
            }
        }
        return false;
    }
}
