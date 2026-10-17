package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.solver.SolveResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * The few numbers about this app worth watching, beside the JVM and HTTP ones
 * Boot already records:
 *
 * <ul>
 * <li>{@code duty.sheet.generate}: how long making a sheet takes, by how it
 * ended (solved, reused from a logged sheet, or not coverable) and with which
 * turnout. The solver is a search; this is where a slow one shows.</li>
 * <li>{@code duty.sheet.logged}: sheets logged, by whether from the board or by
 * saving the image.</li>
 * <li>{@code duty.signin.refused}: sign-ins the throttle turned away. Anything
 * above zero means someone was guessing.</li>
 * </ul>
 *
 * <p>
 * Recorded here, in {@code api/}, and not in the solver, which stays free of
 * Spring and of Micrometer. No tag carries a team, a Trooper or a date: the
 * metrics are for watching the server, and are read by the owner, who sees no
 * team's data.
 */
@Component
public class DutyMetrics {

    private final MeterRegistry registry;
    private final Counter refused;

    public DutyMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.refused = Counter.builder("duty.signin.refused")
                .description("Sign-in attempts refused by the throttle")
                .register(registry);
    }

    /** Make a sheet, timing it and tagging how it ended. */
    SolveResult generate(Integer turnout, Supplier<SolveResult> solve) {
        Timer.Sample sample = Timer.start(registry);
        SolveResult result = solve.get();
        String outcome = !result.isSolved() ? "unsolvable" : result.reusedFrom() != null ? "reused" : "solved";
        sample.stop(Timer.builder("duty.sheet.generate")
                .description("Time to make a sheet")
                .tag("outcome", outcome)
                .tag("turnout", turnout == null ? "free" : turnout + "h")
                .register(registry));
        return result;
    }

    void logged(String via) {
        Counter.builder("duty.sheet.logged").description("Sheets logged").tag("via", via).register(registry).increment();
    }

    void signInRefused() {
        refused.increment();
    }
}
