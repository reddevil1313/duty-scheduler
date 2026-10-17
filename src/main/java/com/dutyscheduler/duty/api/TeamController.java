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
 * The owner's view of the server: which teams there are, and the way into a
 * new one.
 *
 * <p>
 * The owner sees each team's name and how many accounts it has, and nothing
 * of its roster, sheets or leave — their session has no team, so those
 * queries would return nothing, and the team endpoints refuse them outright.
 * A team is made with an invite for its first admin, because an empty team
 * with no way in would be useless, and the owner joining it to get in would
 * break "one team per account".
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

    /**
     * Another invite into a team, for when its admins have all gone or a
     * first-admin link expired unused — the owner's way back in for them.
     */
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
