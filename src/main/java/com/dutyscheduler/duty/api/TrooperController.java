package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.api.dto.TrooperRequest;
import com.dutyscheduler.duty.api.dto.TrooperResponse;
import com.dutyscheduler.duty.persistence.TrooperEntity;
import com.dutyscheduler.duty.persistence.TrooperRepository;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The roster. Anyone signed in may read it; only an ADMIN may add to it.
 *
 * <p>
 * The client cannot ask for a sheet without first knowing who there is to put
 * on it, and hard-coding the names into a React file would put the roster in
 * two places — the one that gets updated and the one that does not.
 */
@RestController
@RequestMapping("/api/troopers")
public class TrooperController {

    private final TrooperRepository troopers;

    public TrooperController(TrooperRepository troopers) {
        this.troopers = troopers;
    }

    @GetMapping
    public List<TrooperResponse> list() {
        // Ordered by id, which is the order they were added in and the order the
        // paper board has always been written in. Alphabetical would be tidier
        // and wrong.
        return troopers.findAllByOrderByIdAsc().stream()
                .map(TrooperResponse::from)
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TrooperResponse> add(@Valid @RequestBody TrooperRequest request) {
        // Trimmed, because the name is matched exactly everywhere it is used. A
        // trailing space would make a second Trooper that looks like the first
        // and never matches a night-group tick.
        String name = request.name().trim();

        // A stay-out is only available 0800–1800, so a default place in the
        // night group is one they could never fill. Well formed, but impossible.
        if (request.stayOut() && request.onNight()) {
            throw new RosterConflictException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A stay-out cannot be in the night group.");
        }
        if (troopers.findByName(name).isPresent()) {
            throw duplicate(name);
        }

        TrooperEntity entity = new TrooperEntity(name, request.stayOut());
        entity.setRank(blankToNull(request.rank()));
        entity.setOnNight(request.onNight());
        try {
            TrooperEntity saved = troopers.saveAndFlush(entity);
            return ResponseEntity.status(HttpStatus.CREATED).body(TrooperResponse.from(saved));
        } catch (DataIntegrityViolationException e) {
            // The check above loses a race with a concurrent add of the same
            // name; the UNIQUE constraint does not. Same answer either way.
            throw duplicate(name);
        }
    }

    private static RosterConflictException duplicate(String name) {
        return new RosterConflictException(HttpStatus.CONFLICT, name + " is already on the roster.");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * A roster change that cannot be made. Carries its own status: 409 when the
     * name is taken, 422 when the request contradicts itself.
     */
    public static class RosterConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final HttpStatus status;

        public RosterConflictException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }
}
