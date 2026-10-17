package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.api.dto.SessionResponse;
import com.dutyscheduler.duty.persistence.AppUserEntity;
import com.dutyscheduler.duty.persistence.AppUserRepository;
import com.dutyscheduler.duty.persistence.TeamEntity;
import com.dutyscheduler.duty.persistence.TeamRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who is signed in, plus the CSRF token. Login and logout are Spring Security
 * filters, configured in {@link SecurityConfig}.
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final AppUserRepository users;
    private final TeamRepository teams;

    public SessionController(AppUserRepository users, TeamRepository teams) {
        this.users = users;
        this.teams = teams;
    }

    /**
     * The {@link CsrfToken} parameter is needed: Spring only creates the token
     * when something asks for it. It rotates on sign-in and sign-out, so the
     * client calls this again after both.
     */
    @GetMapping
    public SessionResponse current(Authentication authentication, CsrfToken csrfToken) {
        // Anonymous callers get an AnonymousAuthenticationToken, which counts as authenticated.
        boolean signedIn = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);

        if (!signedIn) {
            return new SessionResponse(false, null, null, null, null, csrfToken.getToken());
        }

        AppUserEntity user = users.findByUsername(authentication.getName()).orElse(null);
        String role = user != null ? user.getRole() : roleFromAuthorities(authentication);
        String displayName = user != null ? user.getDisplayName() : authentication.getName();
        String team = user == null || user.getTeamId() == null
                ? null
                : teams.findById(user.getTeamId()).map(TeamEntity::getName).orElse(null);

        return new SessionResponse(true, authentication.getName(), displayName, role, team,
                csrfToken.getToken());
    }

    /** {@code ADMIN} from {@code ROLE_ADMIN}. Only for users with no row, i.e. {@code @WithMockUser}. */
    private static String roleFromAuthorities(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(name -> name.startsWith("ROLE_"))
                .map(name -> name.substring("ROLE_".length()))
                .findFirst()
                .orElse(null);
    }
}
