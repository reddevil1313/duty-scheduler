package com.sagarsamay.duty.domain;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * Which posts stand at a given hour.
 *
 * <p>
 * The standing rules, in one place:
 * <ul>
 * <li>2000–2200 on a weekday: no posts.</li>
 * <li>2200–0600, the silent hours: AP and GG.</li>
 * <li>0600–2000: two PAC.</li>
 * <li>0700–1000: BUS as well.</li>
 * <li>Fri 2200 through Mon 0600, the weekend block: AP and GG throughout,
 * and nothing else — no PAC, no BUS.</li>
 * </ul>
 */
public final class Demand {

    private Demand() {
    }

    /**
     * The weekend block runs from Friday 2200 to Monday 0600.
     */
    public static boolean isWeekend(LocalDateTime t) {
        DayOfWeek d = t.getDayOfWeek();
        int h = t.getHour();
        if (d == DayOfWeek.FRIDAY) {
            return h >= 22;
        }
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) {
            return true;
        }
        return d == DayOfWeek.MONDAY && h < 6;
    }

    /** Posts standing at this hour, and how many STs each needs. Never null. */
    public static Map<Post, Integer> at(LocalDateTime t) {
        Map<Post, Integer> need = new EnumMap<>(Post.class);
        int h = t.getHour();

        if (isWeekend(t) || (h >= 22 || h < 6)) {
            need.put(Post.AP, Post.AP.concurrent());
            need.put(Post.GG, Post.GG.concurrent());
            return Map.copyOf(need);
        }

        if (h >= 6 && h < 20) {
            need.put(Post.PAC, Post.PAC.concurrent());
            if (h >= 7 && h < 10) {
                need.put(Post.BUS, Post.BUS.concurrent());
            }
        }
        // 2000-2200 on a weekday falls through with nothing standing.
        return Map.copyOf(need);
    }
}
