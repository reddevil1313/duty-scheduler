package com.dutyscheduler.duty.persistence;

import java.util.Optional;

/**
 * The team for work done with nobody signed in (the dev seeder, test setup).
 * A signed-in account's own team always wins. Use with try-with-resources so
 * it never leaks onto a pooled thread.
 */
public final class TeamScope {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TeamScope() {
    }

    /** The team opened on this thread, if any. */
    public static Optional<Long> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Nested scopes restore the previous one on close. */
    public static Opened open(long teamId) {
        Long previous = CURRENT.get();
        CURRENT.set(teamId);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static void run(long teamId, Runnable work) {
        try (Opened ignored = open(teamId)) {
            work.run();
        }
    }

    /** A scope that can be closed without a checked exception. */
    public interface Opened extends AutoCloseable {
        @Override
        void close();
    }
}
