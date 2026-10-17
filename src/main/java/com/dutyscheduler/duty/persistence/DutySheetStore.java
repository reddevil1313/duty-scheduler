package com.dutyscheduler.duty.persistence;

import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Post;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    /** Silent hours 2200-0559 as slots; the sheet always starts at 2000. */
    static final int SILENT_FROM = 2;
    static final int SILENT_TO = 9;

    private final DutySheetRepository sheets;
    private final TrooperRepository troopers;
    private final AbsenceRepository absences;
    private final HourCreditRepository credits;

    public DutySheetStore(DutySheetRepository sheets, TrooperRepository troopers, AbsenceRepository absences,
            HourCreditRepository credits) {
        this.sheets = sheets;
        this.troopers = troopers;
        this.absences = absences;
        this.credits = credits;
    }

    /** A logged sheet. {@code turnout} is null for the free split. */
    public record Logged(Schedule schedule, Integer turnout, String loggedBy, Instant loggedAt) {
    }

    /** Saves as a free split, replacing any sheet for that date. */
    @Transactional
    public DutySheetEntity save(Schedule schedule, String createdBy) {
        return save(schedule, createdBy, null);
    }

    /** Saves with its turnout, replacing any sheet for that date. */
    @Transactional
    public DutySheetEntity save(Schedule schedule, String createdBy, Integer turnout) {
        LocalDate date = schedule.day().date();
        DutySheetEntity sheet = sheets.findByDutyDate(date).orElse(null);

        if (sheet == null) {
            sheet = new DutySheetEntity(date, createdBy);
        } else {
            // Flush the deletes first: Hibernate inserts before it deletes, which
            // would hit the unique key.
            sheet.getAssignments().clear();
            sheets.saveAndFlush(sheet);
        }
        sheet.setCreatedBy(createdBy);
        sheet.setTurnout(turnout);

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

    @Transactional(readOnly = true)
    public Optional<Schedule> load(LocalDate date) {
        return loadLogged(date).map(Logged::schedule);
    }

    @Transactional(readOnly = true)
    public Optional<Logged> loadLogged(LocalDate date) {
        return sheets.findByDutyDate(date).map(sheet -> new Logged(toDomain(sheet), sheet.getTurnout(), sheet.getCreatedBy(), sheet.getCreatedAt()));
    }

    /** Latest first. */
    @Transactional(readOnly = true)
    public List<Logged> loadAllLogged() {
        return sheets.findAllWithAssignments().stream()
                .map(sheet -> new Logged(toDomain(sheet), sheet.getTurnout(), sheet.getCreatedBy(), sheet.getCreatedAt()))
                .toList();
    }

    private static Schedule toDomain(DutySheetEntity sheet) {
        Map<Trooper, Post[]> grid = new LinkedHashMap<>();
        for (DutyAssignmentEntity assignment : sheet.getAssignments()) {
            Trooper trooper = assignment.getTrooper().toDomain();
            grid.computeIfAbsent(trooper, t -> new Post[DutyDay.SLOT_COUNT])[assignment.getSlot()] = assignment
                    .getPost();
        }
        Schedule.Builder builder = Schedule.builder(DutyDay.of(sheet.getDutyDate()));
        grid.forEach((trooper, cells) -> builder.row(trooper, java.util.Arrays.asList(cells)));
        return builder.build();
    }

    /** Credited hours, by logged date and then Trooper name. */
    @Transactional(readOnly = true)
    public Map<LocalDate, Map<String, Integer>> credits() {
        Map<LocalDate, Map<String, Integer>> out = new LinkedHashMap<>();
        for (HourCreditEntity credit : credits.findAllBy()) {
            out.computeIfAbsent(credit.getSheet().getDutyDate(), d -> new LinkedHashMap<>())
                    .put(credit.getTrooper().getName(), credit.getHours());
        }
        return out;
    }

    /**
     * Replaces a logged day's credited hours. Missing or zero means none.
     *
     * @return false if nothing is logged for that date
     */
    @Transactional
    public boolean saveCredits(LocalDate date, Map<String, Integer> hours) {
        DutySheetEntity sheet = sheets.findByDutyDate(date).orElse(null);
        if (sheet == null) {
            return false;
        }
        List<HourCreditEntity> rows = new ArrayList<>();
        hours.forEach((name, h) -> {
            if (h == null || h < 0 || h > DutyDay.SLOT_COUNT) {
                throw new IllegalArgumentException(name + ": credited hours must be between 0 and 24.");
            }
            if (h == 0) {
                return;
            }
            // Includes archived Troopers.
            TrooperEntity trooper = troopers.findByName(name).orElseThrow(
                    () -> new IllegalArgumentException(name + " is not a Trooper."));
            rows.add(new HourCreditEntity(sheet, trooper, h));
        });
        credits.deleteBySheet(sheet);
        credits.saveAll(rows);
        return true;
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> hoursSince(LocalDate from, LocalDate to) {
        return toCounts(sheets.hoursPerTrooper(from, to));
    }

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

    public static final int HISTORY_DAYS = 90;

    /** Hours each roster Trooper did in the last {@link #HISTORY_DAYS} days. */
    @Transactional(readOnly = true)
    public Map<Trooper, Integer> history(LocalDate upTo) {
        Map<String, Integer> byName = hoursSince(upTo.minusDays(HISTORY_DAYS), upTo.minusDays(1));
        Map<Trooper, Integer> out = new LinkedHashMap<>();
        for (TrooperEntity entity : troopers.findByActiveTrueOrderByIdAsc()) {
            out.put(entity.toDomain(), byName.getOrDefault(entity.getName(), 0));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Absence> allAbsences() {
        return absences.findAllByOrderByFromDateDescIdDesc().stream().map(AbsenceEntity::toDomain).toList();
    }

    /** Leave that reaches the given sheet. */
    @Transactional(readOnly = true)
    public List<Absence> absencesTouching(DutyDay day) {
        return absences.findTouching(day.date().minusDays(1), day.date().plusDays(1))
                .stream()
                .map(AbsenceEntity::toDomain)
                .filter(absence -> absence.touches(day))
                .toList();
    }
}
