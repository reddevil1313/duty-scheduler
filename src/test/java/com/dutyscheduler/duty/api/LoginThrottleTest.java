package com.dutyscheduler.duty.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTest {

    /** A clock the test moves by hand. */
    static final class Hands extends Clock {
        Instant now = Instant.parse("2026-10-17T08:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    final Hands clock = new Hands();
    final LoginThrottle throttle = new LoginThrottle(clock);

    private void fail(String address, int times) {
        for (int i = 0; i < times; i++) {
            throttle.failed(address);
        }
    }

    @Test
    @DisplayName("nine failures are allowed; the tenth shuts the address out for fifteen minutes")
    void limit() {
        fail("10.0.0.1", LoginThrottle.LIMIT - 1);
        assertThat(throttle.refusedUntil("10.0.0.1")).isEmpty();
        fail("10.0.0.1", 1);
        assertThat(throttle.refusedUntil("10.0.0.1")).hasValue(clock.now.plus(LoginThrottle.WINDOW));
    }

    @Test
    @DisplayName("only the address that failed is shut out")
    void perAddress() {
        fail("10.0.0.1", LoginThrottle.LIMIT);
        assertThat(throttle.refusedUntil("10.0.0.2")).isEmpty();
    }

    @Test
    @DisplayName("the address is let back in when the fifteen minutes are up")
    void lifts() {
        fail("10.0.0.1", LoginThrottle.LIMIT);
        clock.advance(LoginThrottle.WINDOW.minusSeconds(1));
        assertThat(throttle.refusedUntil("10.0.0.1")).isPresent();
        clock.advance(Duration.ofSeconds(1));
        assertThat(throttle.refusedUntil("10.0.0.1")).isEmpty();
    }

    @Test
    @DisplayName("failures spread wider than the window never add up to a refusal")
    void windowForgets() {
        fail("10.0.0.1", LoginThrottle.LIMIT - 1);
        clock.advance(LoginThrottle.WINDOW);
        fail("10.0.0.1", LoginThrottle.LIMIT - 1);
        assertThat(throttle.refusedUntil("10.0.0.1")).isEmpty();
    }

    @Test
    @DisplayName("a successful sign-in clears the count")
    void successClears() {
        fail("10.0.0.1", LoginThrottle.LIMIT - 1);
        throttle.succeeded("10.0.0.1");
        fail("10.0.0.1", LoginThrottle.LIMIT - 1);
        assertThat(throttle.refusedUntil("10.0.0.1")).isEmpty();
    }
}
