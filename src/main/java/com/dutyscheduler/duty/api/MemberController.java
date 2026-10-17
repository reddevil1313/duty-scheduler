package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.api.dto.MemberResponse;
import com.dutyscheduler.duty.api.dto.RoleRequest;
import com.dutyscheduler.duty.persistence.AppUserEntity;
import com.dutyscheduler.duty.persistence.AppUserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The accounts in the caller's team. ADMIN only, and only ever the caller's
 * own team: an account in another team is a 404, the same as one that does
 * not exist.
 *
 * <p>
 * A team always keeps at least one admin. Without one nobody could invite,
 * and only the owner could get anyone back in.
 *
 * <p>
 * These are accounts, not Troopers. Removing an account takes away someone's
 * sign-in; it leaves the roster, and every sheet they logged, as it was.
 */
@RestController
@RequestMapping("/api/members")
@PreAuthorize("hasRole('ADMIN')")
public class MemberController {

    private final AppUserRepository users;

    public MemberController(AppUserRepository users) {
        this.users = users;
    }

    @GetMapping
    public List<MemberResponse> list(@AuthenticationPrincipal Account account) {
        return users.findByTeamIdOrderByRoleAscUsernameAsc(Account.teamOf(account)).stream()
                .map(u -> MemberResponse.from(u, account.id())).toList();
    }

    /** Make someone ADMIN or USER. Their next request signs them out, so the new role takes effect at once. */
    @PutMapping("/{id}")
    @Transactional
    public MemberResponse setRole(@PathVariable Long id, @Valid @RequestBody RoleRequest request,
            @AuthenticationPrincipal Account account) {
        AppUserEntity member = member(id, account);
        if (member.getRole().equals("ADMIN") && !request.role().equals("ADMIN")) {
            keepAnAdmin(member);
        }
        member.setRole(request.role());
        return MemberResponse.from(users.save(member), account.id());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> remove(@PathVariable Long id, @AuthenticationPrincipal Account account) {
        AppUserEntity member = member(id, account);
        // Signing yourself out of your own team for good is never what was meant,
        // and done by the last admin it would lock the team.
        if (member.getId() == account.id()) {
            throw new AccountException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "You cannot remove your own account. Ask another admin to.");
        }
        if (member.getRole().equals("ADMIN")) {
            keepAnAdmin(member);
        }
        users.delete(member);
        return ResponseEntity.noContent().build();
    }

    private AppUserEntity member(Long id, Account account) {
        return users.findByIdAndTeamId(id, Account.teamOf(account))
                .orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND, "No such member in your team."));
    }

    private void keepAnAdmin(AppUserEntity admin) {
        if (users.countByTeamIdAndRole(admin.getTeamId(), "ADMIN") <= 1) {
            throw new AccountException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A team needs at least one admin. Make someone else an admin first.");
        }
    }
}
