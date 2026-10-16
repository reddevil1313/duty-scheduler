package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.api.dto.GenerateRequest;
import com.dutyscheduler.duty.api.dto.ScheduleResponse;
import com.dutyscheduler.duty.api.dto.SheetCheck;
import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;
import com.dutyscheduler.duty.persistence.DutySheetStore;
import com.dutyscheduler.duty.persistence.TrooperEntity;
import com.dutyscheduler.duty.persistence.TrooperRepository;
import com.dutyscheduler.duty.rules.ScheduleValidator;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generating, reading and logging duty sheets.
 */
@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleSolver solver;
    private final ScheduleValidator validator;
    private final DutySheetStore store;
    private final TrooperRepository troopers;

    public ScheduleController(ScheduleSolver solver, ScheduleValidator validator, DutySheetStore store,
            TrooperRepository troopers) {
        this.solver = solver;
        this.validator = validator;
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
        List<Trooper> roster = troopers.findByActiveTrueOrderByIdAsc().stream()
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

    /**
     * Check a sheet without saving it: what rules it breaks, who it has on duty
     * during their own leave, and what each hour needed. The board calls this
     * after every hand edit, so the validator that proves the solver correct is
     * the same one that checks a person's changes — there is no second copy of
     * the rules in the browser to drift from it.
     *
     * <p>
     * Saves nothing, so any signed-in user may ask.
     */
    @PostMapping("/{date}/check")
    public SheetCheck check(@PathVariable LocalDate date, @Valid @RequestBody ScheduleResponse body) {
        Schedule schedule = ScheduleAssembler.toDomain(date, body, troopers);
        return SheetCheck.of(schedule, validator.validate(schedule),
                leaveClashes(schedule, store.absencesTouching(schedule.day())));
    }

    /**
     * Hours someone is on duty while their own leave covers them. Not a rule the
     * validator knows — a schedule carries no leave — so it is worked out here,
     * one issue per unbroken run rather than one per hour.
     */
    static List<SheetCheck.Issue> leaveClashes(Schedule schedule, List<Absence> absences) {
        List<SheetCheck.Issue> out = new ArrayList<>();
        DutyDay day = schedule.day();
        for (Schedule.Row row : schedule.rows()) {
            for (Absence absence : absences) {
                if (!absence.trooper().name().equals(row.trooper().name())) {
                    continue;
                }
                int slot = 0;
                while (slot < DutyDay.SLOT_COUNT) {
                    if (row.at(slot) == null || !absence.covers(day.slot(slot))) {
                        slot++;
                        continue;
                    }
                    int first = slot;
                    while (slot < DutyDay.SLOT_COUNT && row.at(slot) != null && absence.covers(day.slot(slot))) {
                        slot++;
                    }
                    int hours = slot - first;
                    out.add(new SheetCheck.Issue(SheetCheck.ON_LEAVE, row.trooper().name(), first,
                            row.trooper().name() + " is on duty from " + day.slot(first).label() + " for "
                                    + hours + "h during their " + absence.kind() + "."));
                }
            }
        }
        return out;
    }

    /**
     * Log a sheet as the one actually used. Replaces whatever was there.
     *
     * <p>
     * Deliberately not validated. The log is the record of what was used, and
     * sometimes what was used broke a rule; refusing it would leave the record
     * wrong instead. The board shows what a sheet breaks, from {@link #check},
     * and asks before logging it anyway.
     */
    @PutMapping("/{date}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ScheduleResponse> log(@PathVariable LocalDate date,
            @Valid @RequestBody ScheduleResponse body,
            Principal principal) {
        Schedule schedule = ScheduleAssembler.toDomain(date, body, troopers);
        store.save(schedule, principal.getName());
        return ResponseEntity.ok(ScheduleResponse.from(schedule));
    }

    /**
     * Replace a logged day's credited hours: hours counted toward someone's
     * total for a day they did not stand, such as an MC or OL day. The grid is
     * left alone. It is the record of who stood what, and editing it to reach
     * a number would put people on posts they never stood.
     */
    @PutMapping("/{date}/credits")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> credit(@PathVariable LocalDate date, @RequestBody Map<String, Integer> hours) {
        return store.saveCredits(date, hours)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /** Thrown when the manpower cannot cover the sheet. Mapped to 422. */
    public static class UnsolvableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UnsolvableException(String message) {
            super(message);
        }
    }
}
