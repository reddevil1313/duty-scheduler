package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.api.dto.AbsenceRequest;
import com.dutyscheduler.duty.api.dto.AbsenceResponse;
import com.dutyscheduler.duty.domain.Absence;
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
                ? absences.findAll()
                : absences.findByTrooperNameOrderByFromDateDesc(trooper);
        return found.stream().map(AbsenceResponse::from).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AbsenceResponse> book(@Valid @RequestBody AbsenceRequest request,
            Principal principal) {
        TrooperEntity trooper = troopers.findByName(request.trooper())
                .orElseThrow(() -> new IllegalArgumentException(
                        request.trooper() + " is not on the roster."));

        Absence absence = new Absence(trooper.toDomain(), request.kind(), request.span(),
                request.from(), request.to(), request.start(), request.end());

        AbsenceEntity saved = absences.save(new AbsenceEntity(trooper, absence, principal.getName()));
        return ResponseEntity.status(HttpStatus.CREATED).body(AbsenceResponse.from(saved));
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
