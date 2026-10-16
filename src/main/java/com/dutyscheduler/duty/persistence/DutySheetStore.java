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

    /**
     * The silent hours as slot indices. A slot's hour is {@code (20 + slot) % 24},
     * so 2200 through 0500 is always slots 2 to 9 whatever the date.
     */
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

    /**
     * A logged sheet and the turnout it was made with: null for the free split.
     * Who logged it, and when, ride along for anything that shows the record.
     */
    public record Logged(Schedule schedule, Integer turnout, String loggedBy, Instant loggedAt) {
    }

    /**
     * Writes a sheet, replacing whatever was there for that date. As the free
     * split; see {@link #save(Schedule, String, Integer)} for a fixed turnout.
     */
    @Transactional
    public DutySheetEntity save(Schedule schedule, String createdBy) {
        return save(schedule, createdBy, null);
    }

    /** Writes a sheet with the turnout it was made with, replacing whatever was there for that date. */
    @Transactional
    public DutySheetEntity save(Schedule schedule, String createdBy, Integer turnout) {
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

    /**
     * Reads a sheet back as a domain object, or nothing if that day was never
     * logged.
     */
    @Transactional(readOnly = true)
    public Optional<Schedule> load(LocalDate date) {
        return loadLogged(date).map(Logged::schedule);
    }

    /** A logged sheet with its turnout, or nothing if that day was never logged. */
    @Transactional(readOnly = true)
    public Optional<Logged> loadLogged(LocalDate date) {
        return sheets.findByDutyDate(date).map(sheet -> new Logged(toDomain(sheet), sheet.getTurnout(), sheet.getCreatedBy(), sheet.getCreatedAt()));
    }

    /** Every logged sheet with its turnout, latest first. */
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
     * Replace a logged day's credited hours. A Trooper missing from the map, or
     * credited zero, has none.
     *
     * @return false when nothing is logged for that date, so there is no day to
     *         credit
     * @throws IllegalArgumentException for a name not on any sheet's roster, or
     *                                  hours outside 0 to 24
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
            // Archived Troopers included: crediting an old day is about who was
            // there then, not who is on the roster now.
            TrooperEntity trooper = troopers.findByName(name).orElseThrow(
                    () -> new IllegalArgumentException(name + " is not a Trooper."));
            rows.add(new HourCreditEntity(sheet, trooper, h));
        });
        credits.deleteBySheet(sheet);
        credits.saveAll(rows);
        return true;
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

    /**
     * How many hours each man has done recently, keyed by the domain object the
     * solver uses.
     */
    public static final int HISTORY_DAYS = 90;

    @Transactional(readOnly = true)
    public Map<Trooper, Integer> history(LocalDate upTo) {
        Map<String, Integer> byName = hoursSince(upTo.minusDays(HISTORY_DAYS), upTo.minusDays(1));
        Map<Trooper, Integer> out = new LinkedHashMap<>();
        for (TrooperEntity entity : troopers.findByActiveTrueOrderByIdAsc()) {
            out.put(entity.toDomain(), byName.getOrDefault(entity.getName(), 0));
        }
        return out;
    }

    /** The whole leave register as domain objects, for counting leave in the records. */
    @Transactional(readOnly = true)
    public List<Absence> allAbsences() {
        return absences.findAllByOrderByFromDateDescIdDesc().stream().map(AbsenceEntity::toDomain).toList();
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
