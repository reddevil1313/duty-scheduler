package com.dutyscheduler.duty.api.dto;

import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Schedule;

import java.time.LocalDate;
import java.util.List;

/**
 * A sheet, flattened for the wire.
 */
public record ScheduleResponse(LocalDate date, List<Row> rows, int totalHours) {

    public record Row(String trooper, boolean stayOut, List<String> posts, int hours, int span) {
    }

    public static ScheduleResponse from(Schedule schedule) {
        List<Row> rows = schedule.rows().stream()
                .map(row -> new Row(
                        row.trooper().name(),
                        row.trooper().stayOut(),
                        cellsOf(row),
                        row.hours(),
                        row.span()))
                .toList();
        int total = rows.stream().mapToInt(Row::hours).sum();
        return new ScheduleResponse(schedule.day().date(), rows, total);
    }

    private static List<String> cellsOf(Schedule.Row row) {
        String[] cells = new String[DutyDay.SLOT_COUNT];
        for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
            cells[slot] = row.at(slot) == null ? null : row.at(slot).name();
        }
        return java.util.Arrays.asList(cells);
    }
}
