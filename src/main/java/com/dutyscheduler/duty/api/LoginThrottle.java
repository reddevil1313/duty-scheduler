package com.dutyscheduler.duty.api;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * After {@link #LIMIT} failed sign-ins in {@link #WINDOW}, an address is
 * refused for {@link #WINDOW}. Per address, not per account, so nobody can
 * lock someone else out by guessing their username.
 *
 * <p>
 * Fed by Spring Security's auth events (form and Basic alike); a success
 * clears the address. In memory, for a single instance. Behind a proxy it
 * relies on {@code server.forward-headers-strategy}.
 */
@Component
public class LoginThrottle {

    static final int LIMIT = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);

    /** Sweep expired entries past this size. */
    private static final int SWEEP_AT = 10_000;

    private final Clock clock;
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    public LoginThrottle() {
        this(Clock.systemUTC());
    }

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** When this address may try again, if it is refused now. */
    Optional<Instant> refusedUntil(String address) {
        Failures f = failures.get(address);
        Instant now = clock.instant();
        return f != null && f.blockedUntil != null && now.isBefore(f.blockedUntil)
                ? Optional.of(f.blockedUntil)
                : Optional.empty();
    }

    void failed(String address) {
        Instant now = clock.instant();
        if (failures.size() >= SWEEP_AT) {
            failures.values().removeIf(f -> f.expired(now));
        }
        failures.compute(address, (a, f) -> {
            Failures next = f == null || f.expired(now) ? new Failures(now) : f;
            next.count++;
            if (next.count >= LIMIT) {
                next.blockedUntil = now.plus(WINDOW);
            }
            return next;
        });
    }

    void succeeded(String address) {
        failures.remove(address);
    }

    @EventListener
    void on(AuthenticationFailureBadCredentialsEvent event) {
        addressOf(event.getAuthentication().getDetails()).ifPresent(this::failed);
    }

    @EventListener
    void on(AuthenticationSuccessEvent event) {
        addressOf(event.getAuthentication().getDetails()).ifPresent(this::succeeded);
    }

    private static Optional<String> addressOf(Object details) {
        return details instanceof WebAuthenticationDetails web ? Optional.ofNullable(web.getRemoteAddress())
                : Optional.empty();
    }

    /** Only changed inside compute(), under the map's lock. */
    private final class Failures {
        private final Instant since;
        private int count;
        private Instant blockedUntil;

        Failures(Instant since) {
            this.since = since;
        }

        boolean expired(Instant now) {
            Instant end = blockedUntil != null ? blockedUntil : since.plus(WINDOW);
            return !now.isBefore(end);
        }
    }
}
