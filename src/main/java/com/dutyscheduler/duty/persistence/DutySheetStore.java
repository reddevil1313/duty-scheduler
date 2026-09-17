package com.dutyscheduler.duty.persistence;

import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Post;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The seam between the domain and the database.
 */
@Service
public class DutySheetStore {

    /**
     * The silent hours as slot indices. A slot's hour is {@code (20 + slot) % 24},
     * so 2200 through 0500 is always slots 2 to 9 whatever the date.
     */
    static final int SILENT_FROM = 2;
    static final int SILENT_TO = 9;

    private final DutySheetRepository sheets;
    private final TrooperRepository troopers;
    private final AbsenceRepository absences;

    public DutySheetStore(DutySheetRepository sheets, TrooperRepository troopers, AbsenceRepository absences) {
        this.sheets = sheets;
        this.troopers = troopers;
        this.absences = absences;
    }

    /**
     * Writes a sheet, replacing whatever was there for that date.
     */
    @Transactional
    public DutySheetEntity save(Schedule schedule, String createdBy) {
        LocalDate date = schedule.day().date();
        DutySheetEntity sheet = sheets.findByDutyDate(date).orElse(null);

        if (sheet == null) {
            sheet = new DutySheetEntity(date, createdBy);
        } else {
            // Clear the old rows and push the DELETEs out before adding replacements.
            // Hibernate flushes inserts-first, deletes-last, so doing both in one go
            // collides with rows that are still there.
            sheet.getAssignments().clear();
            sheets.saveAndFlush(sheet);
        }
        sheet.setCreatedBy(createdBy);

        Map<String, TrooperEntity> byName = new HashMap<>();
        for (TrooperEntity trooper : troopers.findAll()) {
            byName.put(trooper.getName(), trooper);
        }

        List<DutyAssignmentEntity> rows = new ArrayList<>();
        for (Schedule.Row row : schedule.rows()) {
            TrooperEntity trooper = byName.get(row.trooper().name());
            if (trooper == null) {
                throw new IllegalStateException(
                        row.trooper().name() + " is on this sheet but not on the roster.");
            }
            for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
                Post post = row.at(slot);
                if (post != null) {
                    rows.add(new DutyAssignmentEntity(trooper, slot, post));
                }
            }
        }
        sheet.replaceAssignments(rows);
        return sheets.save(sheet);
    }

    /**
     * Reads a sheet back as a domain object, or nothing if that day was never
     * logged.
     */
    @Transactional(readOnly = true)
    public Optional<Schedule> load(LocalDate date) {
        return sheets.findByDutyDate(date).map(sheet -> {
            Map<Trooper, Post[]> grid = new LinkedHashMap<>();
            for (DutyAssignmentEntity assignment : sheet.getAssignments()) {
                Trooper trooper = assignment.getTrooper().toDomain();
                grid.computeIfAbsent(trooper, t -> new Post[DutyDay.SLOT_COUNT])[assignment.getSlot()] = assignment
                        .getPost();
            }
            Schedule.Builder builder = Schedule.builder(DutyDay.of(sheet.getDutyDate()));
            grid.forEach((trooper, cells) -> builder.row(trooper, java.util.Arrays.asList(cells)));
            return builder.build();
        });
    }

    /** Cumulative hours per man, which is what the fairness tiebreak reads. */
    @Transactional(readOnly = true)
    public Map<String, Integer> hoursSince(LocalDate from, LocalDate to) {
        return toCounts(sheets.hoursPerTrooper(from, to));
    }

    /** The same for the silent hours alone. */
    @Transactional(readOnly = true)
    public Map<String, Integer> silentHoursSince(LocalDate from, LocalDate to) {
        return toCounts(sheets.silentHoursPerTrooper(from, to));
    }

    private static Map<String, Integer> toCounts(List<Object[]> rows) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Object[] row : rows) {
            out.put((String) row[0], ((Number) row[1]).intValue());
        }
        return out;
    }

    /** Every booking that could reach the given sheet, as domain objects. */
    @Transactional(readOnly = true)
    public List<Absence> absencesTouching(DutyDay day) {
        return absences.findTouching(day.date().minusDays(1), day.date().plusDays(1))
                .stream()
                .map(AbsenceEntity::toDomain)
                .filter(absence -> absence.touches(day))
                .toList();
    }
}
