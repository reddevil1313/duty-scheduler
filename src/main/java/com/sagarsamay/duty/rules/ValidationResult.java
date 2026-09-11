package com.sagarsamay.duty.rules;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The result of checking a schedule against the rules. Contains a list of violations found.
 * Empty if the schedule is valid.
 *
 * @param violations The list of violations found in the schedule.
 */
public record ValidationResult(List<Violation> violations) {
    public ValidationResult {
        violations = List.copyOf(violations);
    }

    public static ValidationResult clean() {
        return new ValidationResult(List.of());
    }

    public boolean ok() {
        return violations.isEmpty();
    }

    public boolean has(Rule rule) {
        return violations.stream().anyMatch(v -> v.rule() == rule);
    }

    public List<Violation> of(Rule rule) {
        return violations.stream().filter(v -> v.rule() == rule).toList();
    }

    public String describe() {
        return ok() ? "no violations"
                : violations.stream().map(Violation::toString).collect(Collectors.joining("\n"));
    }
    
}
