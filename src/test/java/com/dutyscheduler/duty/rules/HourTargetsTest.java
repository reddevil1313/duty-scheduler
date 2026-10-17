package com.dutyscheduler.duty.rules;

import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Trooper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HourTargetsTest {

    private static final LocalDate THURSDAY = LocalDate.of(2026, 9, 10);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 9, 12);

    private static final Trooper TROOPER_A = Trooper.of("TROOPER_A");
    private static final Trooper TROOPER_B = Trooper.of("TROOPER_B");
    private static final Trooper TROOPER_C = Trooper.of("TROOPER_C");
    private static final Trooper TROOPER_D = Trooper.of("TROOPER_D");
    private static final Trooper TROOPER_E = Trooper.of("TROOPER_E");
    private static final Trooper TROOPER_F = Trooper.of("TROOPER_F");
    private static final Trooper TROOPER_G = Trooper.of("TROOPER_G");
    private static final Trooper TROOPER_H = Trooper.stayOut("TROOPER_H");
    private static final Trooper TROOPER_I = Trooper.stayOut("TROOPER_I");
    private static final Trooper TROOPER_J = Trooper.stayOut("TROOPER_J");
    private static final Trooper TROOPER_K = Trooper.of("TROOPER_K");

    private static final List<Trooper> NIGHT = List.of(TROOPER_A, TROOPER_B, TROOPER_C);
    private static final List<Trooper> DAY =
            List.of(TROOPER_D, TROOPER_E, TROOPER_F, TROOPER_G, TROOPER_H, TROOPER_I, TROOPER_J, TROOPER_K);

    @Test
    @DisplayName("targets add up to demand")
    void targetsAbsorbTheSheet() {
        HourTargets targets = HourTargets.allocate(DutyDay.of(THURSDAY), NIGHT, DAY, Map.of());

        assertEquals(16, targets.nightDemand());
        assertEquals(31, targets.dayDemand());
        assertEquals(47, targets.total());
    }

    @Test
    @DisplayName("within a group, spread is at most 1")
    void eachGroupIsEvenToWithinAnHour() {
        HourTargets targets = HourTargets.allocate(DutyDay.of(THURSDAY), NIGHT, DAY, Map.of());

        assertEquals(1, targets.spreadWithin(NIGHT));
        assertEquals(1, targets.spreadWithin(DAY));
        assertTrue(targets.withinCap());
    }

    /** 16 over 3 is 5-6; 31 over 8 is 3-4. History evens it out over a week. */
    @Test
    @DisplayName("the whole sheet can range 3 to 6")
    void theSheetItselfIsNotEven() {
        HourTargets targets = HourTargets.allocate(DutyDay.of(THURSDAY), NIGHT, DAY, Map.of());

        assertEquals(3, targets.spread());
        assertEquals(List.of(5, 5, 6), NIGHT.stream().map(targets::forTrooper).sorted().toList());
        assertEquals(List.of(3, 4, 4, 4, 4, 4, 4, 4), DAY.stream().map(targets::forTrooper).sorted().toList());
    }

    @Test
    @DisplayName("spare hour to whoever has done least")
    void historyBreaksTheTie() {
        Map<Trooper, Integer> history = Map.of(TROOPER_A, 100, TROOPER_B, 90, TROOPER_C, 80);

        HourTargets targets = HourTargets.allocate(DutyDay.of(THURSDAY), NIGHT, List.of(), history);

        assertEquals(6, targets.forTrooper(TROOPER_C), "lowest cumulative hours takes the extra");
        assertEquals(5, targets.forTrooper(TROOPER_B));
        assertEquals(5, targets.forTrooper(TROOPER_A));
    }

    @Test
    @DisplayName("no history: settled by name")
    void allocationIsDeterministic() {
        HourTargets first = HourTargets.allocate(DutyDay.of(THURSDAY), NIGHT, DAY, Map.of());
        HourTargets second = HourTargets.allocate(DutyDay.of(THURSDAY), NIGHT, DAY, Map.of());

        assertEquals(first.hours(), second.hours());
    }

    @Test
    @DisplayName("weekend splits the same way")
    void weekendSheet() {
        HourTargets targets = HourTargets.allocate(DutyDay.of(SATURDAY), NIGHT, DAY, Map.of());

        assertEquals(16, targets.nightDemand(), "the silent block is the same eight hours");
        assertEquals(32, targets.dayDemand(), "but the daylight hours still stand AP and GG");
        assertEquals(48, targets.total());
    }

    @Test
    @DisplayName("empty group")
    void emptyGroup() {
        assertTrue(HourTargets.distribute(16, List.of(), Map.of()).isEmpty());
    }

    @Test
    @DisplayName("exact division")
    void exactDivision() {
        Map<Trooper, Integer> out = HourTargets.distribute(12, NIGHT, Map.of());

        assertEquals(Map.of(TROOPER_A, 4, TROOPER_B, 4, TROOPER_C, 4), out);
    }
}
