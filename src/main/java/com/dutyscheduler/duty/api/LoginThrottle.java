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
 * Failed sign-ins counted per client address: after {@link #LIMIT} in
 * {@link #WINDOW}, that address is refused for {@link #WINDOW}.
 *
 * <p>
 * By address, not by account (decided by the user, 2026-10-17). Locking an
 * account after a few failures stops guessing at one account, but lets anyone
 * who knows a username lock its owner out; refusing an address only ever slows
 * the address doing the guessing. bcrypt at cost 12 already makes each guess
 * slow; this makes a run of them slower still.
 *
 * <p>
 * Counted from Spring Security's own authentication events, so a failure
 * through the sign-in form and through HTTP Basic count alike, and a success
 * clears the address. In memory: a restart forgets the counts, which costs an
 * attacker nothing they could not get by waiting, and the app runs as one
 * instance. Behind a proxy the address is the client's only because of
 * {@code server.forward-headers-strategy}.
 */
@Component
public class LoginThrottle {

    static final int LIMIT = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);

    /** Beyond this many addresses tracked, expired entries are swept before adding another. */
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

    /** One address's failures in its current window. Mutated only inside compute(), so under the map's lock. */
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
