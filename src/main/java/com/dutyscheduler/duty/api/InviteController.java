package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.api.dto.AcceptInviteRequest;
import com.dutyscheduler.duty.api.dto.InviteRequest;
import com.dutyscheduler.duty.api.dto.InviteResponse;
import com.dutyscheduler.duty.api.dto.InviteView;
import com.dutyscheduler.duty.persistence.AppUserEntity;
import com.dutyscheduler.duty.persistence.AppUserRepository;
import com.dutyscheduler.duty.persistence.InviteEntity;
import com.dutyscheduler.duty.persistence.InviteRepository;
import com.dutyscheduler.duty.persistence.TeamEntity;
import com.dutyscheduler.duty.persistence.TeamRepository;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Invite links. Making, listing and revoking are ADMIN, in their own team.
 * Viewing and accepting are open, guarded by the token only. Every bad token
 * (unknown, used, expired, revoked) gets the same 404.
 */
@RestController
@RequestMapping("/api/invites")
public class InviteController {

    /** Long enough to reach someone on leave. */
    static final Duration LIFETIME = Duration.ofDays(7);

    private static final String NOT_VALID = "This invite link is not valid. It may have expired or been used.";

    private final InviteRepository invites;
    private final AppUserRepository users;
    private final TeamRepository teams;
    private final PasswordEncoder encoder;

    public InviteController(InviteRepository invites, AppUserRepository users, TeamRepository teams,
            PasswordEncoder encoder) {
        this.invites = invites;
        this.users = users;
        this.teams = teams;
        this.encoder = encoder;
    }

    /** The only place the raw token is returned. Also used by TeamController. */
    static InviteResponse issue(InviteRepository invites, long teamId, String role, String createdBy) {
        String token = InviteTokens.newToken();
        InviteEntity saved = invites.save(new InviteEntity(teamId, role, InviteTokens.hash(token), createdBy,
                Instant.now().plus(LIFETIME)));
        return InviteResponse.from(saved, token);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<InviteResponse> create(@Valid @RequestBody InviteRequest request,
            @AuthenticationPrincipal Account account) {
        long team = Account.teamOf(account);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(issue(invites, team, request.role(), account.getUsername()));
    }

    /** Pending invites. Only hashes are stored, so no tokens. */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<InviteResponse> pending(@AuthenticationPrincipal Account account) {
        return invites.findByTeamIdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(Account.teamOf(account),
                Instant.now()).stream().map(i -> InviteResponse.from(i, null)).toList();
    }

    /** Another team's invite is a 404. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> revoke(@PathVariable Long id, @AuthenticationPrincipal Account account) {
        InviteEntity invite = invites.findByIdAndTeamId(id, Account.teamOf(account))
                .orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND, "No such invite in your team."));
        invites.delete(invite);
        return ResponseEntity.noContent().build();
    }

    /** Team, role and expiry, before accepting. */
    @GetMapping("/{token}")
    public InviteView view(@PathVariable String token) {
        InviteEntity invite = open(token);
        String team = teams.findById(invite.getTeamId()).map(TeamEntity::getName).orElseThrow();
        return new InviteView(team, invite.getRole(), invite.getExpiresAt());
    }

    /** One transaction, so a username clash leaves the invite unused. */
    @PostMapping("/{token}/accept")
    @Transactional
    public ResponseEntity<InviteView> accept(@PathVariable String token, @Valid @RequestBody AcceptInviteRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("A password is at most 72 bytes; bcrypt would ignore the rest.");
        }
        InviteEntity invite = open(token);
        String username = request.username();
        if (users.existsByUsernameIgnoreCase(username)) {
            throw new AccountException(HttpStatus.CONFLICT, "The username " + username + " is taken.");
        }
        Instant now = Instant.now();
        if (invites.use(invite.getId(), username, now) == 0) {
            // Used or expired between the look and now.
            throw new AccountException(HttpStatus.NOT_FOUND, NOT_VALID);
        }
        try {
            users.saveAndFlush(new AppUserEntity(username, request.displayName().trim(),
                    encoder.encode(request.password()), invite.getRole(), invite.getTeamId()));
        } catch (DataIntegrityViolationException e) {
            throw new AccountException(HttpStatus.CONFLICT, "The username " + username + " is taken.");
        }
        String team = teams.findById(invite.getTeamId()).map(TeamEntity::getName).orElseThrow();
        return ResponseEntity.status(HttpStatus.CREATED).body(new InviteView(team, invite.getRole(), invite.getExpiresAt()));
    }

    private InviteEntity open(String token) {
        return invites.findByTokenHash(InviteTokens.hash(token))
                .filter(invite -> invite.isOpen(Instant.now()))
                .orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND, NOT_VALID));
    }
}
