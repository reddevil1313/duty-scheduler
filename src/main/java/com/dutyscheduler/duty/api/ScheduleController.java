package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.api.dto.GenerateRequest;
import com.dutyscheduler.duty.api.dto.ScheduleResponse;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;
import com.dutyscheduler.duty.persistence.DutySheetStore;
import com.dutyscheduler.duty.persistence.TrooperEntity;
import com.dutyscheduler.duty.persistence.TrooperRepository;
import com.dutyscheduler.duty.solver.ScheduleSolver;
import com.dutyscheduler.duty.solver.SolveRequest;
import com.dutyscheduler.duty.solver.SolveResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

/**
 * Generating, reading and logging duty sheets.
 */
@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleSolver solver;
    private final DutySheetStore store;
    private final TrooperRepository troopers;

    public ScheduleController(ScheduleSolver solver, DutySheetStore store, TrooperRepository troopers) {
        this.solver = solver;
        this.store = store;
        this.troopers = troopers;
    }

    /**
     * Works out a sheet without saving it.
     */
    @PostMapping("/generate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ScheduleResponse> generate(@Valid @RequestBody GenerateRequest request) {
        DutyDay day = DutyDay.of(request.date());
        List<Trooper> roster = troopers.findAllByOrderByIdAsc().stream()
                .map(TrooperEntity::toDomain).toList();
        List<Trooper> night = roster.stream()
                .filter(t -> request.nightGroup().contains(t.name()))
                .toList();

        SolveResult result = solver.solve(
                new SolveRequest(day, roster, night, store.absencesTouching(day), store.history(day.date())));

        if (!result.isSolved()) {
            throw new UnsolvableException(result.reason());
        }
        return ResponseEntity.ok(ScheduleResponse.from(result.schedule()));
    }

    @GetMapping("/{date}")
    public ResponseEntity<ScheduleResponse> read(@PathVariable LocalDate date) {
        return store.load(date)
                .map(ScheduleResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /** Log a sheet as the one actually used. Replaces whatever was there. */
    @PutMapping("/{date}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ScheduleResponse> log(@PathVariable LocalDate date,
            @Valid @RequestBody ScheduleResponse body,
            Principal principal) {
        Schedule schedule = ScheduleAssembler.toDomain(date, body, troopers);
        store.save(schedule, principal.getName());
        return ResponseEntity.ok(ScheduleResponse.from(schedule));
    }

    /** Thrown when the manpower cannot cover the sheet. Mapped to 422. */
    public static class UnsolvableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UnsolvableException(String message) {
            super(message);
        }
    }
}
