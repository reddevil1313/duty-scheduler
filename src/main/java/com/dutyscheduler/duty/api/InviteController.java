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
 * Invite links: an admin makes them for their own team, and anyone holding
 * one can see what it is for and accept it.
 *
 * <p>
 * The two halves have opposite access. Making, listing and revoking are
 * ADMIN, and only ever in the caller's own team. Viewing and accepting are
 * open to anyone — the person accepting has no account yet — and are guarded
 * by the token alone, which is why every way a token can fail (unknown, used,
 * expired, revoked) gives the same 404: telling them apart would only tell
 * someone probing which of their guesses had once been real.
 */
@RestController
@RequestMapping("/api/invites")
public class InviteController {

    /** Long enough to reach someone on leave; short enough that a link left in a chat dies. */
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

    /** Make an invite and hand back its token, once; shared by the owner's team creation. */
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

    /** The team's invites still waiting to be used. Their tokens are not stored, so not shown. */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<InviteResponse> pending(@AuthenticationPrincipal Account account) {
        return invites.findByTeamIdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(Account.teamOf(account),
                Instant.now()).stream().map(i -> InviteResponse.from(i, null)).toList();
    }

    /** Revoke: the row goes, so the link finds nothing. Another team's invite is a 404 like any unknown one. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> revoke(@PathVariable Long id, @AuthenticationPrincipal Account account) {
        InviteEntity invite = invites.findByIdAndTeamId(id, Account.teamOf(account))
                .orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND, "No such invite in your team."));
        invites.delete(invite);
        return ResponseEntity.noContent().build();
    }

    /** What a link is for, shown before anyone commits to it. Open to anyone with the token. */
    @GetMapping("/{token}")
    public InviteView view(@PathVariable String token) {
        InviteEntity invite = open(token);
        String team = teams.findById(invite.getTeamId()).map(TeamEntity::getName).orElseThrow();
        return new InviteView(team, invite.getRole(), invite.getExpiresAt());
    }

    /**
     * Accept: make the account, in the invite's team and role, and use the
     * invite up. One transaction, so a username lost to a race leaves the
     * invite unused for another try.
     */
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
