package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.api.dto.TrooperResponse;
import com.dutyscheduler.duty.persistence.TrooperRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The roster.
 *
 * <p>
 * Read-only, and read by anyone signed in. The client cannot ask for a sheet
 * without first knowing who there is to put on it, and hard-coding eleven names
 * into a React file would put the roster in two places — the one that gets
 * updated and the one that does not.
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
        // Ordered by id, which is the order they were seeded in and the order the
        // paper board has always been written in. Alphabetical would be tidier
        // and wrong.
        return troopers.findAllByOrderByIdAsc().stream()
                .map(TrooperResponse::from)
                .toList();
    }
}
