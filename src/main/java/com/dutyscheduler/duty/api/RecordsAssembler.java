package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.api.dto.RecordsResponse;
import com.dutyscheduler.duty.api.dto.RecordsResponse.DayRecord;
import com.dutyscheduler.duty.api.dto.RecordsResponse.TrooperRecord;
import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Post;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.persistence.TrooperEntity;
import com.dutyscheduler.duty.rules.ScheduleValidator;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds the records up from logged sheets, credits, opening hours and the leave
 * register. Everything is derived on each read rather than stored as totals,
 * so a re-logged day or a cancelled MC is reflected the moment it happens and
 * there is no running total to fall out of step.
 */
final class RecordsAssembler {

    /** The silent hours as slots: 2200 to 0559 is always slots 2 to 9. */
    private static final int SILENT_FROM = 2;
    private static final int SILENT_TO = 9;

    private RecordsAssembler() {
    }

    /**
     * @param troopers every Trooper, archived included, in roster order
     * @param sheets   every logged sheet, latest first
     * @param credits  credited hours by date, then name
     */
    static RecordsResponse assemble(List<TrooperEntity> troopers, List<Schedule> sheets,
            Map<LocalDate, Map<String, Integer>> credits, List<Absence> leave, ScheduleValidator validator) {

        Map<String, Tally> byName = new LinkedHashMap<>();
        // Active first, then archived, each in the order they were added.
        troopers.stream().filter(TrooperEntity::isActive).forEach(t -> byName.put(t.getName(), new Tally(t)));
        troopers.stream().filter(t -> !t.isActive()).forEach(t -> byName.put(t.getName(), new Tally(t)));

        List<DayRecord> days = new ArrayList<>();
        for (Schedule sheet : sheets) {
            Map<String, Integer> credited = credits.getOrDefault(sheet.day().date(), Map.of());
            List<String> night = new ArrayList<>();
            List<String> bus = new ArrayList<>();
            int worked = 0;
            for (Schedule.Row row : sheet.rows()) {
                Tally tally = byName.get(row.trooper().name());
                int hours = row.hours();
                worked += hours;
                boolean onNight = false;
                boolean onBus = false;
                for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
                    Post post = row.at(slot);
                    onNight |= post != null && slot >= SILENT_FROM && slot <= SILENT_TO;
                    onBus |= post == Post.BUS;
                }
                if (onNight) night.add(row.trooper().name());
                if (onBus) bus.add(row.trooper().name());
                if (tally != null && hours > 0) {
                    tally.worked += hours;
                    tally.duties++;
                    if (onNight) tally.nights++;
                    if (onBus) tally.bus++;
                }
            }
            credited.forEach((name, hours) -> {
                Tally tally = byName.get(name);
                if (tally != null) tally.credited += hours;
            });
            int creditedTotal = credited.values().stream().mapToInt(Integer::intValue).sum();
            days.add(new DayRecord(sheet.day().date(), worked, creditedTotal, Map.copyOf(credited),
                    List.copyOf(night), List.copyOf(bus),
                    validator.validate(sheet).violations().size()));
        }

        for (Absence absence : leave) {
            Tally tally = byName.get(absence.trooper().name());
            if (tally == null) continue;
            double amount = switch (absence.span()) {
                case FULL -> ChronoUnit.DAYS.between(absence.from(), absence.to()) + 1;
                case DAY_HALF, NIGHT_HALF -> 0.5;
                case WINDOW -> 1;
            };
            switch (absence.kind()) {
                case MC -> tally.mc += amount;
                case OL -> tally.ol += amount;
                case AL -> tally.al += amount;
                case MA -> tally.ma++;
            }
        }

        List<TrooperRecord> records = byName.values().stream()
                // An archived Trooper is listed only while there is something to show for them.
                .filter(t -> t.entity.isActive() || t.hasHistory())
                .map(Tally::toRecord)
                .toList();
        return new RecordsResponse(records, List.copyOf(days));
    }

    private static final class Tally {
        final TrooperEntity entity;
        int worked, credited, duties, nights, bus, ma;
        double mc, ol, al;

        Tally(TrooperEntity entity) {
            this.entity = entity;
        }

        boolean hasHistory() {
            return entity.getOpeningHours() > 0 || worked > 0 || credited > 0
                    || mc > 0 || ol > 0 || al > 0 || ma > 0;
        }

        TrooperRecord toRecord() {
            int opening = entity.getOpeningHours();
            return new TrooperRecord(entity.getName(), entity.isActive(), opening, worked, credited,
                    opening + worked + credited, duties, nights, bus, mc, ol, al, ma);
        }
    }
}
