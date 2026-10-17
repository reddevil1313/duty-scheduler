package com.dutyscheduler.duty.solver;

import com.dutyscheduler.duty.domain.DutyRules;
import com.dutyscheduler.duty.domain.Post;
import com.dutyscheduler.duty.domain.Schedule;

import java.util.Comparator;

/**
 * How good a legal schedule is.
 *
 * The comparison is lexicographic, worst-off Trooper first.
 *
 * @param postChanges walks between AP and GG within a turnout, summed over
 *                    everyone; see {@link DutyRules#postChanges}
 */
public record ScheduleScore(int hourSpread, int worstExcess, int totalExcess, int postChanges) {

    /**
     * Lowest hour spread, then worst excess span, then total excess span, then
     * fewest AP/GG walks. Walks are last so they never cost fairness.
     */
    public static final Comparator<ScheduleScore> BEST_FIRST =
            Comparator.comparingInt(ScheduleScore::hourSpread)
                    .thenComparingInt(ScheduleScore::worstExcess)
                    .thenComparingInt(ScheduleScore::totalExcess)
                    .thenComparingInt(ScheduleScore::postChanges);

    /**
     * Scores a sheet on wasted span.
     */
    public static ScheduleScore of(Schedule schedule) {
        int worst = 0;
        int total = 0;
        int fewest = Integer.MAX_VALUE;
        int most = 0;
        int changes = 0;
        for (Schedule.Row row : schedule.rows()) {
            // Stay-outs still walk, even though span doesn't count for them.
            changes += DutyRules.postChanges(row.posts().toArray(new Post[0]));
            if (row.hours() > 0) {
                fewest = Math.min(fewest, row.hours());
                most = Math.max(most, row.hours());
            }
            if (row.trooper().stayOut()) { // stay-out troopers don't count toward excess span
                continue;
            }
            int excess = row.excessSpan();
            worst = Math.max(worst, excess);
            total += excess;
        }
        int spread = fewest == Integer.MAX_VALUE ? 0 : most - fewest;
        return new ScheduleScore(spread, worst, total, changes);
    }

    /**
     * Returns true if this schedule is better than the given one.
     */
    public boolean isBetterThan(ScheduleScore other) {
        return other == null || BEST_FIRST.compare(this, other) < 0;
    }

    @Override
    public String toString() {
        return "hours within " + hourSpread + "h; wasted span worst " + worstExcess
                + "h, total " + totalExcess + "h; " + postChanges + " AP/GG changes";
    }
}
