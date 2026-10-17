package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.api.dto.TrooperRequest;
import com.dutyscheduler.duty.api.dto.TrooperResponse;
import com.dutyscheduler.duty.persistence.DutyAssignmentRepository;
import com.dutyscheduler.duty.persistence.HourCreditRepository;
import com.dutyscheduler.duty.persistence.TrooperEntity;
import com.dutyscheduler.duty.persistence.TrooperRepository;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The roster. Anyone signed in may read it; only an ADMIN may change it.
 *
 * <p>
 * The client cannot ask for a sheet without first knowing who there is to put
 * on it, and hard-coding the names into a React file would put the roster in
 * two places — the one that gets updated and the one that does not.
 *
 * <p>
 * Removing a Trooper archives them if they stand on any logged sheet or have
 * credited hours, and
 * deletes them outright otherwise. A logged sheet is the record of what was
 * actually used; deleting the Trooper with it would rewrite that record.
 */
@RestController
@RequestMapping("/api/troopers")
// A team's own accounts only. The owner has no team, so would read nothing
// here anyway; refusing them outright says so. Methods that write narrow this
// to ADMIN with their own annotation, which takes precedence over this one.
@PreAuthorize("hasAnyRole('ADMIN', 'USER')")
public class TrooperController {

    private final TrooperRepository troopers;
    private final DutyAssignmentRepository assignments;
    private final HourCreditRepository credits;

    public TrooperController(TrooperRepository troopers, DutyAssignmentRepository assignments,
            HourCreditRepository credits) {
        this.troopers = troopers;
        this.assignments = assignments;
        this.credits = credits;
    }

    @GetMapping
    public List<TrooperResponse> list() {
        // Ordered by id, which is the order they were added in and the order the
        // paper board has always been written in. Alphabetical would be tidier
        // and wrong.
        return troopers.findByActiveTrueOrderByIdAsc().stream()
                .map(TrooperResponse::from)
                .toList();
    }

    /**
     * Add a Trooper. A name that belongs to an archived Trooper brings that
     * Trooper back, with these particulars, rather than refusing: names are
     * unique across archived and active alike, so the alternative is a name
     * nobody can ever use again.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<TrooperResponse> add(@Valid @RequestBody TrooperRequest request) {
        String name = nameOf(request);
        checkConsistent(request);

        TrooperEntity entity = troopers.findByName(name).orElse(null);
        if (entity != null && entity.isActive()) {
            throw duplicate(name);
        }
        if (entity == null) {
            entity = new TrooperEntity(name, request.stayOut());
        }
        takeDefaultBus(entity.getId(), request);
        entity.setActive(true);
        apply(entity, name, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(TrooperResponse.from(save(entity, name)));
    }

    /** Replace a Trooper's particulars, name included. */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public TrooperResponse update(@PathVariable Long id, @Valid @RequestBody TrooperRequest request) {
        TrooperEntity entity = onRoster(id);
        String name = nameOf(request);
        checkConsistent(request);

        troopers.findByName(name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw other.isActive()
                            ? duplicate(name)
                            : new RosterException(HttpStatus.CONFLICT, name
                                    + " belongs to an archived Trooper. Add them back instead.");
                });

        takeDefaultBus(id, request);
        apply(entity, name, request);
        return TrooperResponse.from(save(entity, name));
    }

    /** Take a Trooper off the roster: archived if they have logged duty, deleted if not. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> remove(@PathVariable Long id) {
        TrooperEntity entity = onRoster(id);
        // Credited hours are history too, and their foreign key would refuse
        // the delete just the same.
        if (assignments.existsByTrooper_Id(id) || credits.existsByTrooper_Id(id)) {
            archive(entity);
            return ResponseEntity.noContent().build();
        }
        try {
            // Their leave goes with them: absence cascades on delete in V1.
            troopers.delete(entity);
            troopers.flush();
        } catch (DataIntegrityViolationException e) {
            // A sheet naming them was logged between the check and the delete.
            // The foreign key refused the delete, which is the point of it.
            archive(troopers.findById(id).orElseThrow());
        }
        return ResponseEntity.noContent().build();
    }

    private void archive(TrooperEntity entity) {
        entity.setActive(false);
        // Not a default night-group member, nor the default BUS Trooper, any
        // more, should they be added back.
        entity.setOnNight(false);
        entity.setDefaultBus(false);
        troopers.save(entity);
    }

    private TrooperEntity onRoster(Long id) {
        // Archived counts as not found. Editing someone who is not on the roster
        // is a request about nobody the client can see.
        return troopers.findByIdAndActiveTrue(id).orElseThrow(() -> new RosterException(
                HttpStatus.NOT_FOUND, "No Trooper " + id + " on the roster."));
    }

    /**
     * One default BUS Trooper at most: whoever had it gives it up first, or the
     * partial unique index refuses the write. Called before the entity is
     * changed: it is managed in this transaction, so the bulk update flushes it
     * first, and flushed already holding the flag it would meet the old holder
     * in the index. In the same transaction as the save, so a save refused
     * later puts the old default back rather than leaving nobody holding it.
     * A new Trooper has no id yet, and "id <> null" matches nobody in SQL, so
     * -1 stands in for it.
     */
    private void takeDefaultBus(Long id, TrooperRequest request) {
        if (Boolean.TRUE.equals(request.defaultBus())) {
            troopers.clearDefaultBusExcept(id == null ? -1L : id);
        }
    }

    private TrooperEntity save(TrooperEntity entity, String name) {
        try {
            return troopers.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            // The name checks lose a race with a concurrent add or rename to the
            // same name; the UNIQUE constraint does not. Same answer either way.
            throw duplicate(name);
        }
    }

    private static String nameOf(TrooperRequest request) {
        // Trimmed, because the name is matched exactly everywhere it is used. A
        // trailing space would make a second Trooper that looks like the first
        // and never matches a night-group tick.
        return request.name().trim();
    }

    private static void checkConsistent(TrooperRequest request) {
        // A stay-out is only available 0800–1800, so a default place in the
        // night group is one they could never fill. Well formed, but impossible.
        if (request.stayOut() && request.onNight()) {
            throw new RosterException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A stay-out cannot be in the night group.");
        }
        // Not in until 0800, so never able to take the 0700 run.
        if (request.stayOut() && Boolean.TRUE.equals(request.defaultBus())) {
            throw new RosterException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A stay-out cannot be the default BUS Trooper: they are not in until 0800.");
        }
    }

    private static void apply(TrooperEntity entity, String name, TrooperRequest request) {
        entity.setName(name);
        entity.setRank(request.rank() == null || request.rank().isBlank() ? null : request.rank().trim());
        entity.setStayOut(request.stayOut());
        entity.setOnNight(request.onNight());
        // Left alone when the request does not say, so an edit of the name does
        // not quietly clear it; given up when they become a stay-out, who could
        // never take the run.
        if (request.defaultBus() != null) {
            entity.setDefaultBus(request.defaultBus());
        }
        if (request.stayOut()) {
            entity.setDefaultBus(false);
        }
    }

    private static RosterException duplicate(String name) {
        return new RosterException(HttpStatus.CONFLICT, name + " is already on the roster.");
    }

    /**
     * A roster change that cannot be made. Carries its own status: 404 for
     * nobody on the roster with that id, 409 when the name is taken, 422 when
     * the request contradicts itself.
     */
    public static class RosterException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final HttpStatus status;

        public RosterException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }
}
