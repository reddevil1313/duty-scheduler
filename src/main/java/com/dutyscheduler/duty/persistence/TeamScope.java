package com.dutyscheduler.duty.persistence;

import java.util.Optional;

/**
 * The team this thread works for when nobody is signed in to say: the dev
 * seeder at startup, and the tests that seed a roster before they make a
 * request.
 *
 * <p>
 * A signed-in account's own team always wins over this, so a scope left open
 * by mistake cannot hand anyone a team that is not theirs — at most it gives
 * work done with nobody signed in a team. Opened with try-with-resources, so
 * it closes on every path; a scope that outlived its work would stay on a
 * pooled thread for the next job to find.
 *
 * <p>
 * Plain Java, and here rather than beside the resolver in {@code api/},
 * because the seeders and the store are what open it and they have no
 * business knowing about Spring Security.
 */
public final class TeamScope {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TeamScope() {
    }

    /** The team opened on this thread, if any. */
    public static Optional<Long> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Work as this team until the result is closed. Nested scopes restore the
     * one they replaced, so a test can seed a second team inside its own.
     */
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
