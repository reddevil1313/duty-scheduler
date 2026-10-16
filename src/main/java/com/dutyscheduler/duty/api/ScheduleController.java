package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.api.dto.GenerateRequest;
import com.dutyscheduler.duty.api.dto.QuickLeave;
import com.dutyscheduler.duty.api.dto.ScheduleResponse;
import com.dutyscheduler.duty.api.dto.SheetCheck;
import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.AbsenceKind;
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
        List<Absence> ticked = ticked(day, request.out());
        // Someone ticked out cannot hold the silent hours, whatever box was left
        // ticked for them on the board.
        List<Trooper> night = roster.stream()
                .filter(t -> request.nightGroup().contains(t.name()))
                .filter(t -> ticked.stream().noneMatch(a -> a.trooper().equals(t)))
                .toList();
        List<Absence> absences = new ArrayList<>(store.absencesTouching(day));
        absences.addAll(ticked);

        Trooper bus = null;
        if (request.bus() != null && !request.bus().isBlank()) {
            bus = roster.stream().filter(t -> t.name().equals(request.bus())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(request.bus() + " is not on the roster."));
        }

        // A BUS choice the chosen Trooper cannot stand comes back unsolved, with
        // the reason, before any search — and leaves as a 422 like any sheet
        // that cannot be covered.
        SolveResult result = solver.solve(
                new SolveRequest(day, roster, night, absences, store.history(day.date()), bus));

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
    public SheetCheck check(@PathVariable LocalDate date, @Valid @RequestBody ScheduleResponse body,
            @RequestParam(name = "out", required = false) List<String> out) {
        Schedule schedule = ScheduleAssembler.toDomain(date, body, troopers);
        List<Absence> absences = new ArrayList<>(store.absencesTouching(schedule.day()));
        absences.addAll(ticked(schedule.day(), parseTicks(out)));
        return SheetCheck.of(schedule, validator.validate(schedule), leaveClashes(schedule, absences), absences);
    }

    /**
     * Ticks from the roster as leave covering the whole sheet: from midnight on
     * its date, past 2000 the next evening when it ends. Never saved; they live
     * as long as the request.
     */
    private List<Absence> ticked(DutyDay day, List<QuickLeave> out) {
        if (out == null) {
            return List.of();
        }
        List<Absence> absences = new ArrayList<>();
        for (QuickLeave tick : out) {
            if (tick.kind() == AbsenceKind.MA) {
                throw new IllegalArgumentException(
                        "A tick is MC, OL or AL. Book an appointment in the leave register.");
            }
            // The roster's own Trooper, not one built from the name: the solver
            // matches leave to a Trooper by name and stay-out together.
            Trooper trooper = troopers.findByNameAndActiveTrue(tick.trooper())
                    .orElseThrow(() -> new IllegalArgumentException(tick.trooper() + " is not on the roster."))
                    .toDomain();
            absences.add(Absence.fullDays(trooper, tick.kind(), day.date(), day.date().plusDays(1)));
        }
        return absences;
    }

    /**
     * Ticks on a check arrive as {@code out=NAME:KIND} query parameters, because
     * the body is the sheet itself. Split on the last colon, so a colon in a
     * name cannot shift the kind.
     */
    private static List<QuickLeave> parseTicks(List<String> out) {
        if (out == null) {
            return List.of();
        }
        List<QuickLeave> ticks = new ArrayList<>();
        for (String tick : out) {
            int colon = tick.lastIndexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("A tick is NAME:KIND, such as TROOPER_D:MC; got " + tick);
            }
            ticks.add(new QuickLeave(tick.substring(0, colon), AbsenceKind.valueOf(tick.substring(colon + 1))));
        }
        return ticks;
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
