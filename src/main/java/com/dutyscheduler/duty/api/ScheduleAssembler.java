package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.api.dto.ScheduleResponse;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Post;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;
import com.dutyscheduler.duty.persistence.TrooperEntity;
import com.dutyscheduler.duty.persistence.TrooperRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a posted sheet back into a domain object.
 *
 * <p>
 * An Admin can hand-edit the board before logging it, so what arrives
 * here is not necessarily what the solver produced — it has to be rebuilt
 * properly rather than trusted.
 */
final class ScheduleAssembler {

    private ScheduleAssembler() {
    }

    static Schedule toDomain(LocalDate date, ScheduleResponse body, TrooperRepository troopers) {
        Schedule.Builder builder = Schedule.builder(DutyDay.of(date));
        for (ScheduleResponse.Row row : body.rows()) {
            TrooperEntity entity = troopers.findByName(row.trooper())
                    .orElseThrow(() -> new IllegalArgumentException(
                            row.trooper() + " is not on the roster."));
            Trooper trooper = entity.toDomain();

            if (row.posts().size() != DutyDay.SLOT_COUNT) {
                throw new IllegalArgumentException(
                        row.trooper() + " has " + row.posts().size() + " cells, expected " + DutyDay.SLOT_COUNT);
            }
            List<Post> cells = new ArrayList<>(DutyDay.SLOT_COUNT);
            for (String cell : row.posts()) {
                cells.add(cell == null || cell.isBlank() ? null : Post.valueOf(cell));
            }
            builder.row(trooper, cells);
        }
        return builder.build();
    }
}
