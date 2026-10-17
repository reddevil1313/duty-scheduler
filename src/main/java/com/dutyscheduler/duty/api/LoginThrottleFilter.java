package com.dutyscheduler.duty.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Refuses a sign-in attempt from an address {@link LoginThrottle} has shut out,
 * before the password is checked: a refused address gets no answer about
 * whether its guess was right.
 *
 * <p>
 * A sign-in attempt is the login POST, or any request carrying HTTP Basic
 * credentials. Everything else passes, so an address that is shut out can
 * still reach the session endpoint and be told why.
 */
final class LoginThrottleFilter extends OncePerRequestFilter {

    private final LoginThrottle throttle;
    /** Matched as Spring Security matches it: the servlet path alone is empty under some mappings. */
    private final RequestMatcher login;

    LoginThrottleFilter(LoginThrottle throttle, String loginPath) {
        this.throttle = throttle;
        this.login = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, loginPath);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean attempt = login.matches(request)
                || String.valueOf(request.getHeader("Authorization")).startsWith("Basic ");
        if (attempt) {
            Instant until = throttle.refusedUntil(request.getRemoteAddr()).orElse(null);
            if (until != null) {
                long seconds = Math.max(1, Duration.between(Instant.now(), until).toSeconds());
                long minutes = (seconds + 59) / 60;
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader("Retry-After", String.valueOf(seconds));
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                response.getWriter().write("""
                        {"type":"about:blank","title":"Too many sign-in attempts","status":429,\
                        "detail":"Too many failed sign-ins from here. Try again in %d minute%s."}"""
                        .formatted(minutes, minutes == 1 ? "" : "s"));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
