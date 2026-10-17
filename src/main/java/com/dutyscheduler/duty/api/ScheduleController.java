package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.api.dto.GenerateRequest;
import com.dutyscheduler.duty.api.dto.QuickLeave;
import com.dutyscheduler.duty.api.dto.ScheduleResponse;
import com.dutyscheduler.duty.api.dto.SheetCheck;
import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.AbsenceKind;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Post;
import com.dutyscheduler.duty.domain.DayDemand;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;
import com.dutyscheduler.duty.persistence.DutySheetStore;
import com.dutyscheduler.duty.persistence.TrooperEntity;
import com.dutyscheduler.duty.persistence.TrooperRepository;
import com.dutyscheduler.duty.rules.Availability;
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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Generating, reading and logging duty sheets. */
@RestController
@RequestMapping("/api/schedules")
// Not the owner, who has no team. Write methods narrow this to ADMIN.
@PreAuthorize("hasAnyRole('ADMIN', 'USER')")
public class ScheduleController {

    private final ScheduleSolver solver;
    private final ScheduleValidator validator;
    private final DutySheetStore store;
    private final TrooperRepository troopers;
    private final DutyMetrics metrics;

    public ScheduleController(ScheduleSolver solver, ScheduleValidator validator, DutySheetStore store,
            TrooperRepository troopers, DutyMetrics metrics) {
        this.solver = solver;
        this.validator = validator;
        this.store = store;
        this.troopers = troopers;
        this.metrics = metrics;
    }

    /** Works out a sheet without saving it. */
    @PostMapping("/generate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ScheduleResponse> generate(@Valid @RequestBody GenerateRequest request) {
        DutyDay day = DutyDay.of(request.date());
        List<Trooper> roster = troopers.findByActiveTrueOrderByIdAsc().stream()
                .map(TrooperEntity::toDomain).toList();
        List<Absence> ticked = ticked(day, request.out());
        // Ticked out wins over a night-group tick.
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

        List<Schedule> reusable = reusable(day, roster, absences, request.turnout());
        Trooper pick = bus;
        SolveResult result = metrics.generate(request.turnout(), () -> {
            SolveResult first = solver.solve(
                    new SolveRequest(day, roster, night, absences, store.history(day.date()), pick,
                            request.turnout(), reusable));
            // If the roster's default BUS can't do it today, let the solver pick.
            // Only an explicit pick is refused.
            if (!first.isSolved() && pick != null && Boolean.TRUE.equals(request.busDefault())) {
                return solver.solve(
                        new SolveRequest(day, roster, night, absences, store.history(day.date()), null,
                                request.turnout(), reusable));
            }
            return first;
        });

        if (!result.isSolved()) {
            throw new UnsolvableException(result.reason());
        }
        // Night, day, stay-outs, then anyone with no free hour (weekend stay-outs too).
        Set<String> unavailable = roster.stream()
                .filter(t -> !availableAtAll(t, day, absences))
                .map(Trooper::name)
                .collect(Collectors.toSet());
        return ResponseEntity.ok(ScheduleResponse.from(result.schedule(), request.turnout(), result.reusedFrom())
                .inBoardOrder(Availability.silentSlots(day), unavailable));
    }

    private static boolean availableAtAll(Trooper trooper, DutyDay day, List<Absence> absences) {
        for (boolean free : Availability.mask(trooper, day, absences)) {
            if (free) {
                return true;
            }
        }
        return false;
    }

    /**
     * Logged sheets that could be reused, latest first: same posts by hour
     * (Mon-Thu all match), same headcount, same turnout. Never this date's own
     * sheet, since regenerating should give something new.
     */
    private List<Schedule> reusable(DutyDay day, List<Trooper> roster, List<Absence> absences, Integer turnout) {
        long headcount = roster.stream().filter(t -> availableAtAll(t, day, absences)).count();
        List<Map<Post, Integer>> posts = DayDemand.of(day).byHour();
        return store.loadAllLogged().stream()
                .filter(l -> !l.schedule().day().equals(day))
                .filter(l -> Objects.equals(l.turnout(), turnout))
                .filter(l -> l.schedule().rows().stream().filter(r -> r.hours() > 0).count() == headcount)
                .filter(l -> DayDemand.of(l.schedule().day()).byHour().equals(posts))
                .map(DutySheetStore.Logged::schedule)
                .toList();
    }

    @GetMapping("/{date}")
    public ResponseEntity<ScheduleResponse> read(@PathVariable LocalDate date) {
        return store.loadLogged(date)
                .map(l -> ScheduleResponse.from(l.schedule(), l.turnout(), null))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * Rule breaks, duty during leave, and each hour's demand. The board calls
     * this after every edit so the rules only live on the server. Saves nothing.
     */
    @PostMapping("/{date}/check")
    public SheetCheck check(@PathVariable LocalDate date, @Valid @RequestBody ScheduleResponse body,
            @RequestParam(name = "out", required = false) List<String> out) {
        Schedule schedule = ScheduleAssembler.toDomain(date, body, troopers);
        List<Absence> absences = new ArrayList<>(store.absencesTouching(schedule.day()));
        absences.addAll(ticked(schedule.day(), parseTicks(out)));
        return SheetCheck.of(schedule, validator.validate(schedule), leaveClashes(schedule, absences), absences);
    }

    /** Roster ticks as whole-sheet leave, for this request only. */
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
            // Must be the roster's Trooper: leave matches on name and stay-out.
            Trooper trooper = troopers.findByNameAndActiveTrue(tick.trooper())
                    .orElseThrow(() -> new IllegalArgumentException(tick.trooper() + " is not on the roster."))
                    .toDomain();
            absences.add(Absence.fullDays(trooper, tick.kind(), day.date(), day.date().plusDays(1)));
        }
        return absences;
    }

    /** {@code out=NAME:KIND} params (the body is the sheet). Splits on the last colon. */
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
     * Duty during the person's own leave, one issue per run. The validator
     * can't see this since a schedule has no leave in it.
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
     * Logs the sheet actually used, replacing any earlier one. Not validated:
     * the record should match what happened, even if it broke a rule. The board
     * warns first.
     */
    @PutMapping("/{date}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ScheduleResponse> log(@PathVariable LocalDate date,
            @Valid @RequestBody ScheduleResponse body,
            Principal principal) {
        Schedule schedule = ScheduleAssembler.toDomain(date, body, troopers);
        Integer turnout = checkedTurnout(body.turnout());
        store.save(schedule, principal.getName(), turnout);
        metrics.logged("board");
        return ResponseEntity.ok(ScheduleResponse.from(schedule, turnout, null));
    }

    /** Replaces a logged day's credited hours (counted, but not on the grid). */
    @PutMapping("/{date}/credits")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> credit(@PathVariable LocalDate date, @RequestBody Map<String, Integer> hours) {
        return store.saveCredits(date, hours)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /** 5 to 7, or null for the free split. */
    static Integer checkedTurnout(Integer turnout) {
        if (turnout != null && (turnout < SolveRequest.MIN_TURNOUT || turnout > SolveRequest.MAX_TURNOUT)) {
            throw new IllegalArgumentException("A fixed turnout is 5 to 7 hours, not " + turnout + ".");
        }
        return turnout;
    }

    /** Thrown when the manpower cannot cover the sheet. Mapped to 422. */
    public static class UnsolvableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UnsolvableException(String message) {
            super(message);
        }
    }
}
