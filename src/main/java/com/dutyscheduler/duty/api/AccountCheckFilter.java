package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.persistence.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/**
 * Ends a session whose account has been removed, or whose role or team has
 * changed since it signed in.
 *
 * <p>
 * The session holds the account as it was at sign-in — the role every check
 * reads and the team every query is filtered by. Without this, someone an
 * admin removed would keep their access until the session timed out, and a
 * demoted admin would stay an admin until they signed out themselves.
 *
 * <p>
 * The request carries on as anonymous rather than failing here: the session
 * endpoint then answers "signed out" with a 200, which is what tells the
 * client to show the sign-in form, and anything that needs an account gets the
 * usual 401. One lookup by primary key per signed-in request.
 */
final class AccountCheckFilter extends OncePerRequestFilter {

    private final AppUserRepository users;

    AccountCheckFilter(AppUserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Account account) {
            boolean current = users.findById(account.id())
                    .filter(user -> user.getRole().equals(account.role())
                            && Objects.equals(user.getTeamId(), account.teamId()))
                    .isPresent();
            if (!current) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
            }
        }
        chain.doFilter(request, response);
    }
}
