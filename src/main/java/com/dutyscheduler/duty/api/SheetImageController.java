package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.api.dto.ScheduleResponse;
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

/**
 * The finalised sheet as an image, for sending on.
 *
 * <p>
 * Producing the image logs the sheet. What was sent is then always what was
 * recorded: there is no image of a sheet that was never logged, and no logged
 * sheet that differs from the image that went out.
 */
@RestController
@RequestMapping("/api/schedules")
// A team's own accounts only. The owner has no team, so would read nothing
// here anyway; refusing them outright says so. Methods that write narrow this
// to ADMIN with their own annotation, which takes precedence over this one.
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
     * Draw the sheet, log it, and send the drawing.
     *
     * <p>
     * In that order, so the two either both happen or neither does. The image
     * is drawn first, in memory: if drawing fails, nothing has been logged.
     * Then the sheet is logged in one transaction: if that fails, the image is
     * thrown away and the caller gets the error instead. Only once the log has
     * committed are the bytes sent.
     */
    @PostMapping("/{date}/image")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> logAndDraw(@PathVariable LocalDate date, @Valid @RequestBody ScheduleResponse body,
            Principal principal) {
        Schedule schedule = ScheduleAssembler.toDomain(date, body, troopers);
        Integer turnout = ScheduleController.checkedTurnout(body.turnout());
        byte[] png = SheetImage.png(schedule, turnout, principal.getName(), Instant.now());
        store.save(schedule, principal.getName(), turnout);
        metrics.logged("image");
        return image(date, png);
    }

    /**
     * The image of a sheet already logged, drawn from the record, for sending it
     * on again. Anyone signed in may have it; it changes nothing.
     */
    @GetMapping("/{date}/image")
    public ResponseEntity<byte[]> draw(@PathVariable LocalDate date) {
        return store.loadLogged(date)
                .map(l -> image(date, SheetImage.png(l.schedule(), l.turnout(),
                        l.loggedBy() == null ? "unknown" : l.loggedBy(),
                        l.loggedAt() == null ? Instant.now() : l.loggedAt())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    private static ResponseEntity<byte[]> image(LocalDate date, byte[] png) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("duty-sheet-" + date + ".png").build().toString())
                .body(png);
    }
}
