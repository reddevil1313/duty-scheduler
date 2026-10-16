package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.api.dto.RecordsResponse;
import com.dutyscheduler.duty.persistence.DutySheetStore;
import com.dutyscheduler.duty.persistence.TrooperEntity;
import com.dutyscheduler.duty.persistence.TrooperRepository;
import com.dutyscheduler.duty.rules.ScheduleValidator;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The records: totals per Trooper and a line per day logged. Anyone signed in
 * may read them; only an ADMIN may set opening hours.
 */
@RestController
@RequestMapping("/api/records")
public class RecordsController {

    private final DutySheetStore store;
    private final TrooperRepository troopers;
    private final ScheduleValidator validator;

    public RecordsController(DutySheetStore store, TrooperRepository troopers, ScheduleValidator validator) {
        this.store = store;
        this.troopers = troopers;
        this.validator = validator;
    }

    @GetMapping
    public RecordsResponse records() {
        return RecordsAssembler.assemble(troopers.findAllByOrderByIdAsc(), store.loadAll(), store.credits(),
                store.allAbsences(), validator);
    }

    /**
     * Set opening hours, by name. Troopers left out keep what they had, so the
     * form can send only what changed.
     *
     * <p>
     * All or nothing: one bad entry and none are saved, rather than half a
     * form's worth landing silently.
     */
    @PutMapping("/opening")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public RecordsResponse setOpening(@RequestBody Map<String, Integer> hours) {
        List<TrooperEntity> changed = new ArrayList<>();
        hours.forEach((name, h) -> {
            if (h == null || h < 0) {
                throw new IllegalArgumentException(name + ": opening hours cannot be negative.");
            }
            TrooperEntity trooper = troopers.findByName(name)
                    .orElseThrow(() -> new IllegalArgumentException(name + " is not a Trooper."));
            trooper.setOpeningHours(h);
            changed.add(trooper);
        });
        troopers.saveAll(changed);
        return records();
    }
}
