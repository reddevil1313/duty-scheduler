package com.sagarsamay.duty.domain;

/**
 * A Trooper in the roster.
 * 
 * @param name The name of the trooper.
 * @param stayOut Whether the trooper is staying out i.e. available only on weekdays between 0800-1800.
 */
public record Trooper(String name, Boolean stayOut) {

    public Trooper {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A Trooper must have a name.");
        }
        name = name.trim().toUpperCase();
    }

    public static Trooper of(String name) {
        return new Trooper(name, false);
    }

    public static Trooper stayOut(String name) {
        return new Trooper(name, true);
    }

    @Override 
    public String toString() {
        return stayOut ? name + " (stay-out)" : name;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Trooper t && name.equals(t.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

}