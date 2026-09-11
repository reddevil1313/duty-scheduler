package com.dutyscheduler.duty.rules;

import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.DayDemand;
import com.dutyscheduler.duty.domain.Demand;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.DutyRules;
import com.dutyscheduler.duty.domain.Slot;
import com.dutyscheduler.duty.domain.Trooper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A trooper's availability for a duty sheet, and how many hours can be placed
 * inside it.
 */
public final class Availability {

    private Availability() {
    }

    /**
     * A mask of the twenty-four hours that are open to a trooper on a given sheet.
     * 
     * <p>Hours are open if the trooper is not absent, and if he is a stay-out trooper
     * then only the hours between 0800 and 1800 on weekdays are open.
     */
    public static boolean[] mask(Trooper trooper, DutyDay day, List<Absence> absences) {
        List<Absence> trooperAbsences = absences.stream()
                .filter(absence -> absence.trooper().equals(trooper))
                .filter(absence -> absence.touches(day))
                .toList();
        
        boolean[] mask = new boolean[DutyDay.SLOT_COUNT];
        for (int i = 0; i < DutyDay.SLOT_COUNT; i++) {
            Slot slot = day.slot(i);
            mask[i] = !blocked(trooper, slot, trooperAbsences);
        }
        return mask;
    }

    /** True if the trooper is absent or stay-out at any point in this hour. */
    private static boolean blocked(Trooper trooper, Slot slot, List<Absence> trooperAbsences) {
        for (Absence absence : trooperAbsences) {
            if (absence.covers(slot)) {
                return true;
            }
        }

        if (!trooper.stayOut()) {
            return false;
        }

        LocalDateTime t = slot.start();
        if (Demand.isWeekend(t)) {
            return true;
        }

        return t.getHour() < DutyRules.STAY_OUT_FROM || t.getHour() >= DutyRules.STAY_OUT_TO;
    }

    /** How many of the twenty-four hours are open to him at all. */
    public static int freeHours(boolean[] mask) {
        int free = 0;
        for (boolean open: mask) {
            if (open) {
                free++;
            }
        }
        return free;
    }

    /**
     * Most duty hours that can actually be placed inside a mask, once 3-on/1-off
     * is applied to each unbroken run.
     *
     * <p>This is an <em>upper bound</em>, and deliberately so. Used as a
     * prune so that it can only ever over-estimate what is reachable, which
     * means it never cuts off a schedule that exists.
     */
    public static int capacity(boolean[] mask) {
        int capacity = 0;
        int run = 0;
        for (boolean open: mask) {
            if (open) {
                run++;
            } else {
                capacity += DutyRules.stintCapacity(run);
                run = 0;
            }
        }

        return capacity + DutyRules.stintCapacity(run);
    }

    /** The same, restricted to a window — pass the window as a second mask. */
    public static int capacityWithin(boolean[] mask, boolean[] window) {
        boolean[] combined = new boolean[DutyDay.SLOT_COUNT];
        for (int i = 0; i < DutyDay.SLOT_COUNT; i++) {
            combined[i] = mask[i] && window[i];
        }
        return capacity(combined);
    }

    /**
     * The silent hours, 2200 to 0600, as a mask over this sheet.
     */
    public static boolean[] silentSlots(DutyDay day) {
        boolean[] silent = new boolean[DutyDay.SLOT_COUNT];
        for (int i = 0; i < DutyDay.SLOT_COUNT; i++) {
            Slot slot = day.slot(i);
            silent[i] = slot.start().getHour() >= 22 || slot.start().getHour() < 6;
        }
        return silent;
    }

    /** The rest of the sheet. */
    public static boolean[] daySlots(DutyDay day) {
        boolean[] silent = silentSlots(day);
        boolean[] out = new boolean[DutyDay.SLOT_COUNT];
        for (int i = 0; i < DutyDay.SLOT_COUNT; i++) {
            out[i] = !silent[i];
        }
        return out;
    }

    /** The demand for Trooper-hours the sheet needs inside a given window. */
    public static int demandWithin(DutyDay day, boolean[] window) {
        DayDemand demand = DayDemand.of(day);
        int total = 0;
        for (int i = 0; i < DutyDay.SLOT_COUNT; i++) {
            if (window[i]) {
                total += demand.headcountAt(i);
            }
        }
        return total;
    }

    /**
     * The smallest night group that could possibly cover the silent hours.
     * 
     * E.g. 16hrs silent demand, 6hrs per trooper, means minimum 3 troopers needed at night.
     */
    public static int minimumNightGroup(DutyDay day) {
        boolean[] silent = silentSlots(day);
        int demand = demandWithin(day, silent);
        if (demand == 0) {
            return 0;
        }
        int hoursPerTrooper = capacity(silent);
        return (demand + hoursPerTrooper - 1) / hoursPerTrooper; // ceiling division
    }
}
