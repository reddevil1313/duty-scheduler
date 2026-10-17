package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.api.dto.AbsenceReach;
import com.dutyscheduler.duty.api.dto.AbsenceRequest;
import com.dutyscheduler.duty.api.dto.AbsenceResponse;
import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.Trooper;
import com.dutyscheduler.duty.persistence.AbsenceEntity;
import com.dutyscheduler.duty.persistence.AbsenceRepository;
import com.dutyscheduler.duty.persistence.TrooperEntity;
import com.dutyscheduler.duty.persistence.TrooperRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

/**
 * The leave register. Anyone signed in may read it; only an ADMIN may change
 * it.
 */
@RestController
@RequestMapping("/api/absences")
// Not the owner, who has no team. Write methods narrow this to ADMIN.
@PreAuthorize("hasAnyRole('ADMIN', 'USER')")
public class AbsenceController {

    private final AbsenceRepository absences;
    private final TrooperRepository troopers;

    public AbsenceController(AbsenceRepository absences, TrooperRepository troopers) {
        this.absences = absences;
        this.troopers = troopers;
    }

    @GetMapping
    public List<AbsenceResponse> list(@RequestParam(required = false) String trooper) {
        List<AbsenceEntity> found = trooper == null
                ? absences.findAllByOrderByFromDateDescIdDesc()
                : absences.findByTrooperNameOrderByFromDateDesc(trooper);
        return found.stream().map(AbsenceResponse::from).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AbsenceResponse> book(@Valid @RequestBody AbsenceRequest request,
            Principal principal) {
        TrooperEntity trooper = troopers.findByNameAndActiveTrue(request.trooper())
                .orElseThrow(() -> new IllegalArgumentException(
                        request.trooper() + " is not on the roster."));

        AbsenceEntity entity = new AbsenceEntity(trooper, toDomain(trooper.toDomain(), request),
                principal.getName());
        entity.setNote(request.note() == null || request.note().isBlank() ? null : request.note().trim());
        AbsenceEntity saved = absences.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(AbsenceResponse.from(saved));
    }

    /**
     * Which sheets a booking would reach, without saving it. The form calls
     * this while it is filled in.
     */
    @PostMapping("/preview")
    public AbsenceReach preview(@Valid @RequestBody AbsenceRequest request) {
        return AbsenceReach.of(toDomain(new Trooper(request.trooper(), false), request));
    }

    private static Absence toDomain(Trooper trooper, AbsenceRequest request) {
        return new Absence(trooper, request.kind(), request.span(),
                request.from(), request.to(), request.start(), request.end());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> cancel(@PathVariable Long id) {
        if (!absences.findById(id).isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        absences.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
