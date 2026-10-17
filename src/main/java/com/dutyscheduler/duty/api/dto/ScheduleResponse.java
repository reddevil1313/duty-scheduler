package com.dutyscheduler.duty.api.dto;

import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Schedule;

import java.time.LocalDate;
import java.util.List;
import java.util.Comparator;
import java.util.Set;

/**
 * A sheet, flattened for the wire.
 *
 * @param turnout    null for the free split; sent back when logging
 * @param reusedFrom date of the logged sheet reused, if any
 */
public record ScheduleResponse(LocalDate date, List<Row> rows, int totalHours, Integer turnout,
        LocalDate reusedFrom) {

    public record Row(String trooper, boolean stayOut, List<String> posts, int hours, int span) {
    }

    public static ScheduleResponse from(Schedule schedule) {
        return from(schedule, null, null);
    }

    public static ScheduleResponse from(Schedule schedule, Integer turnout, LocalDate reusedFrom) {
        List<Row> rows = schedule.rows().stream()
                .map(row -> new Row(
                        row.trooper().name(),
                        row.trooper().stayOut(),
                        cellsOf(row),
                        row.hours(),
                        row.span()))
                .toList();
        int total = rows.stream().mapToInt(Row::hours).sum();
        return new ScheduleResponse(schedule.day().date(), rows, total, turnout, reusedFrom);
    }

    /**
     * Rows in board order: night (anyone with a silent hour), day, stay-outs,
     * then anyone away all sheet; roster order within each. Must match
     * {@code boardOrder} in web/src/board/edit.ts.
     *
     * @param silent which slots are the silent hours
     * @param away   who cannot stand any hour of this sheet
     */
    public ScheduleResponse inBoardOrder(boolean[] silent, Set<String> away) {
        List<Row> ordered = rows.stream()
                .sorted(Comparator.comparingInt(r -> group(r, silent, away)))
                .toList();
        return new ScheduleResponse(date, ordered, totalHours, turnout, reusedFrom);
    }

    private static int group(Row row, boolean[] silent, Set<String> away) {
        if (away.contains(row.trooper())) {
            return 3;
        }
        for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
            if (silent[slot] && row.posts().get(slot) != null) {
                return 0;
            }
        }
        return row.stayOut() ? 2 : 1;
    }

    private static List<String> cellsOf(Schedule.Row row) {
        String[] cells = new String[DutyDay.SLOT_COUNT];
        for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
            cells[slot] = row.at(slot) == null ? null : row.at(slot).name();
        }
        return java.util.Arrays.asList(cells);
    }
}
