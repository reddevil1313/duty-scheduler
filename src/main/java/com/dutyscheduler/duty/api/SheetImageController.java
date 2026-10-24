package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.api.dto.ScheduleResponse;
import com.dutyscheduler.duty.api.dto.SheetCheck;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.export.SheetImage;
import com.dutyscheduler.duty.persistence.DutySheetStore;
import com.dutyscheduler.duty.persistence.TrooperRepository;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The sheet as an image. Making the image logs the sheet, so every image sent
 * out matches the record.
 */
@RestController
@RequestMapping("/api/schedules")
// Not the owner, who has no team. Write methods narrow this to ADMIN.
@PreAuthorize("hasAnyRole('ADMIN', 'USER')")
public class SheetImageController {

    private final DutySheetStore store;
    private final TrooperRepository troopers;

    private final DutyMetrics metrics;

    public SheetImageController(DutySheetStore store, TrooperRepository troopers, DutyMetrics metrics) {
        this.store = store;
        this.troopers = troopers;
        this.metrics = metrics;
    }

    /**
     * Draw, then log, then send. If drawing fails nothing is logged; if
     * logging fails nothing is sent.
     */
    @PostMapping("/{date}/image")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> logAndDraw(@PathVariable LocalDate date, @Valid @RequestBody ScheduleResponse body,
            Principal principal) {
        Schedule schedule = ScheduleAssembler.toDomain(date, body, troopers);
        Integer turnout = ScheduleController.checkedTurnout(body.turnout());
        byte[] png = SheetImage.png(schedule, turnout, away(schedule), principal.getName(), Instant.now());
        store.save(schedule, principal.getName(), turnout);
        metrics.logged("image");
        return image(date, png);
    }

    /** An already logged sheet, drawn again. */
    @GetMapping("/{date}/image")
    public ResponseEntity<byte[]> draw(@PathVariable LocalDate date) {
        return store.loadLogged(date)
                .map(l -> image(date, SheetImage.png(l.schedule(), l.turnout(), away(l.schedule()),
                        l.loggedBy() == null ? "unknown" : l.loggedBy(),
                        l.loggedAt() == null ? Instant.now() : l.loggedAt())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /** Booked leave only: roster ticks are whole-sheet, so those rows have no hours to draw. */
    private Map<String, List<String>> away(Schedule schedule) {
        return SheetCheck.away(schedule.day(), store.absencesTouching(schedule.day()),
                schedule.rows().stream().map(Schedule.Row::trooper).toList());
    }

    private static ResponseEntity<byte[]> image(LocalDate date, byte[] png) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("duty-sheet-" + date + ".png").build().toString())
                .body(png);
    }
}
