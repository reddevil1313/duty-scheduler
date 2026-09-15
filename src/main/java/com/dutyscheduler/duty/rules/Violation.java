package com.dutyscheduler.duty.rules;

/**
 * A specific violation of a rule in a schedule. This is used to report violations found in a schedule.
 *
 * @param rule The rule that was violated.
 * @param who The Trooper who violated the rule, if applicable.
 * @param slot The slot (hour) at which the violation occurred, if applicable.
 * @param message A message describing the violation.
 */
public record Violation(Rule rule, String who, Integer slot, String message) {
    public static Violation at(Rule rule, int slot, String message) {
        return new Violation(rule, null, slot, message);
    }

    public static Violation by(Rule rule, String who, Integer slot, String message) {
        return new Violation(rule, who, slot, message);
    }

    @Override
    public String toString() {
        return rule + ": " + message;
    }
    
}
