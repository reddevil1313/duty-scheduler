package com.dutyscheduler.duty.solver;

import com.dutyscheduler.duty.domain.DayDemand;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Post;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;
import com.dutyscheduler.duty.rules.Availability;
import com.dutyscheduler.duty.rules.Rule;
import com.dutyscheduler.duty.rules.ScheduleValidator;
import com.dutyscheduler.duty.rules.ValidationResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Reuses a logged sheet for today: the old rows stay exactly as they were,
 * only who stands each one changes. A row can go to anyone who:
 * <ul>
 * <li>is available for all of its hours;
 * <li>is not on night, or it has silent hours (a hand-edited sheet may have
 * put day Troopers on silent hours too);
 * <li>is the chosen BUS Trooper, for the row with the BUS run.
 * </ul>
 * Longer rows go to whoever has done the fewest hours. The result must pass
 * the validator, otherwise the caller solves from scratch.
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
        int n = available.size();
        boolean[][] fits = new boolean[n][n]; // [trooper][row]
        for (int t = 0; t < n; t++) {
            Trooper trooper = available.get(t);
            boolean[] free = Availability.mask(trooper, day, request.absences());
            boolean night = request.nightGroup().contains(trooper);
            for (int r = 0; r < n; r++) {
                fits[t][r] = canStand(rows.get(r), free, night, silent)
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

    private static boolean canStand(List<Post> row, boolean[] free, boolean night, boolean[] silent) {
        boolean anySilent = false;
        for (int slot = 0; slot < row.size(); slot++) {
            if (row.get(slot) == null) {
                continue;
            }
            if (!free[slot]) {
                return false;
            }
            anySilent |= silent[slot];
        }
        return !night || anySilent;
    }

    /** The chosen BUS Trooper's row has the BUS run, and nobody else's does. */
    private static boolean busAllows(Trooper bus, Trooper trooper, List<Post> row) {
        if (bus == null) {
            return true;
        }
        boolean hasBus = row.contains(Post.BUS);
        return trooper.equals(bus) ? hasBus : !hasBus;
    }

    /**
     * Free split: no violations. Fixed turnout: only gaps the MP team may take
     * (not silent, someone still on).
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
