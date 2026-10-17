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
 * Ends the session if the account was removed or its role or team changed
 * since sign-in; otherwise a removed user keeps access until the session
 * expires. The request continues as anonymous, so the client sees
 * "signed out" and other endpoints give their usual 401.
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
