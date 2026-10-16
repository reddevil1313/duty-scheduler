package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.api.dto.SessionResponse;
import com.dutyscheduler.duty.persistence.AppUserEntity;
import com.dutyscheduler.duty.persistence.AppUserRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one endpoint a browser client calls before it can do anything else.
 *
 * <p>
 * There is no matching POST here. Signing in and out are handled by Spring
 * Security's own filters at {@code /api/session/login} and
 * {@code /api/session/logout} — they never reach a controller, which is why you
 * will not find methods for them. {@link SecurityConfig} is where their
 * responses are shaped.
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final AppUserRepository users;

    public SessionController(AppUserRepository users) {
        this.users = users;
    }

    /**
     * Identity and a fresh CSRF token.
     *
     * <p>
     * Taking {@link CsrfToken} as a parameter is not decoration. Spring
     * Security defers loading the token — it is not generated, and the cookie is
     * not written, until something actually asks for it. Asking for it here is
     * what makes it exist. A client that skipped this call and went straight to
     * a POST would be refused with a 403 and no obvious reason why.
     *
     * <p>
     * The token is also the reason this must be called again after signing in
     * and after signing out: both rotate it, and the one held from before is
     * dead.
     */
    @GetMapping
    public SessionResponse current(Authentication authentication, CsrfToken csrfToken) {
        // An anonymous caller is not null here — Spring hands out an
        // AnonymousAuthenticationToken, and isAuthenticated() returns true on it.
        // Checking only for null would report every visitor as signed in.
        boolean signedIn = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);

        if (!signedIn) {
            return new SessionResponse(false, null, null, null, csrfToken.getToken());
        }

        AppUserEntity user = users.findByUsername(authentication.getName()).orElse(null);
        String role = user != null ? user.getRole() : roleFromAuthorities(authentication);
        String displayName = user != null ? user.getDisplayName() : authentication.getName();

        return new SessionResponse(true, authentication.getName(), displayName, role,
                csrfToken.getToken());
    }

    /**
     * The role as the client wants it — {@code ADMIN}, not {@code ROLE_ADMIN}.
     *
     * <p>
     * Only used when there is no row to read it from, which in practice means a
     * test with {@code @WithMockUser}. Worth having: without it those tests
     * would 500 on an endpoint that is supposed to be the simplest in the app.
     */
    private static String roleFromAuthorities(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(name -> name.startsWith("ROLE_"))
                .map(name -> name.substring("ROLE_".length()))
                .findFirst()
                .orElse(null);
    }
}
