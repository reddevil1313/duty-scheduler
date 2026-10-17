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
 * The roster. Anyone in the team can read it; only an ADMIN can change it.
 *
 * <p>
 * Removing a Trooper who is on a logged sheet or has credited hours archives
 * them, so old sheets stay intact. Otherwise they are deleted.
 */
@RestController
@RequestMapping("/api/troopers")
// Not the owner, who has no team. Write methods narrow this to ADMIN.
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
        // Order added, which is the order the paper board uses.
        return troopers.findByActiveTrueOrderByIdAsc().stream()
                .map(TrooperResponse::from)
                .toList();
    }

    /**
     * Adds a Trooper. An archived Trooper's name brings them back, since names
     * are unique across archived ones too.
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

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> remove(@PathVariable Long id) {
        TrooperEntity entity = onRoster(id);
        if (assignments.existsByTrooper_Id(id) || credits.existsByTrooper_Id(id)) {
            archive(entity);
            return ResponseEntity.noContent().build();
        }
        try {
            troopers.delete(entity); // their leave cascades
            troopers.flush();
        } catch (DataIntegrityViolationException e) {
            // A sheet with them on it was logged in the meantime.
            archive(troopers.findById(id).orElseThrow());
        }
        return ResponseEntity.noContent().build();
    }

    private void archive(TrooperEntity entity) {
        entity.setActive(false);
        entity.setOnNight(false);
        entity.setDefaultBus(false);
        troopers.save(entity);
    }

    private TrooperEntity onRoster(Long id) {
        // Archived counts as not found.
        return troopers.findByIdAndActiveTrue(id).orElseThrow(() -> new RosterException(
                HttpStatus.NOT_FOUND, "No Trooper " + id + " on the roster."));
    }

    /**
     * Clears the old default BUS so the unique index accepts the new one.
     * Must run before the entity is changed: the bulk update flushes it, and
     * flushed with the flag set it would clash with the old holder. A new
     * Trooper has no id, and {@code id <> null} matches nothing, hence -1.
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
            // Lost a race to the same name.
            throw duplicate(name);
        }
    }

    private static String nameOf(TrooperRequest request) {
        // Names are matched exactly everywhere.
        return request.name().trim();
    }

    private static void checkConsistent(TrooperRequest request) {
        // Stay-outs are only in 0800-1800.
        if (request.stayOut() && request.onNight()) {
            throw new RosterException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A stay-out cannot be in the night group.");
        }
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
        // null leaves it as is.
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

    /** 404 not on the roster, 409 name taken, 422 contradictory request. */
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
