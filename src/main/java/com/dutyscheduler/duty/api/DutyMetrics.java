package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.solver.SolveResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * App metrics on top of Boot's JVM and HTTP ones:
 * <ul>
 * <li>{@code duty.sheet.generate}: timer by outcome (solved, reused,
 * unsolvable) and turnout</li>
 * <li>{@code duty.sheet.logged}: by board or image</li>
 * <li>{@code duty.signin.refused}: throttled sign-ins</li>
 * </ul>
 * Tags never include a team, Trooper or date.
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

    /** Times a generate and tags its outcome. */
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
