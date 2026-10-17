package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.api.dto.InviteRequest;
import com.dutyscheduler.duty.api.dto.InviteResponse;
import com.dutyscheduler.duty.api.dto.TeamCreated;
import com.dutyscheduler.duty.api.dto.TeamRequest;
import com.dutyscheduler.duty.api.dto.TeamResponse;
import com.dutyscheduler.duty.persistence.AppUserRepository;
import com.dutyscheduler.duty.persistence.InviteRepository;
import com.dutyscheduler.duty.persistence.TeamEntity;
import com.dutyscheduler.duty.persistence.TeamRepository;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * Owner only: list teams (names and account counts, no data) and create one.
 * A new team comes with an invite for its first admin.
 */
@RestController
@RequestMapping("/api/teams")
@PreAuthorize("hasRole('OWNER')")
public class TeamController {

    private final TeamRepository teams;
    private final AppUserRepository users;
    private final InviteRepository invites;

    public TeamController(TeamRepository teams, AppUserRepository users, InviteRepository invites) {
        this.teams = teams;
        this.users = users;
        this.invites = invites;
    }

    @GetMapping
    public List<TeamResponse> list() {
        return teams.findAllByOrderByNameAsc().stream().map(this::summary).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<TeamCreated> create(@Valid @RequestBody TeamRequest request, Principal principal) {
        String name = request.name().trim();
        if (teams.findByNameIgnoreCase(name).isPresent()) {
            throw new AccountException(HttpStatus.CONFLICT, "There is already a team called " + name + ".");
        }
        TeamEntity team;
        try {
            team = teams.saveAndFlush(new TeamEntity(name));
        } catch (DataIntegrityViolationException e) {
            throw new AccountException(HttpStatus.CONFLICT, "There is already a team called " + name + ".");
        }
        InviteResponse invite = InviteController.issue(invites, team.getId(), "ADMIN", principal.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new TeamCreated(summary(team), invite));
    }

    /** For when a team has lost its admins, or the first link expired. */
    @PostMapping("/{id}/invites")
    public ResponseEntity<InviteResponse> invite(@PathVariable Long id, @Valid @RequestBody InviteRequest request,
            Principal principal) {
        TeamEntity team = teams.findById(id)
                .orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND, "No team " + id + "."));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(InviteController.issue(invites, team.getId(), request.role(), principal.getName()));
    }

    private TeamResponse summary(TeamEntity team) {
        return new TeamResponse(team.getId(), team.getName(), team.getCreatedAt(),
                users.countByTeamIdAndRole(team.getId(), "ADMIN"), users.countByTeamIdAndRole(team.getId(), "USER"));
    }
}
