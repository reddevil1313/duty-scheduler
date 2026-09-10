# Duty Scheduler

Generates guard duty schedules for an 11-man team under hard constraints
(post coverage, 3h max stint, 1h min break, stay-out windows, leave, hour fairness).

## Conventions
- Java 21, Spring Boot 3. Constructor injection only — no @Autowired on fields.
- Flyway owns the schema. Never `ddl-auto: update`.
- Domain logic in `domain/` stays free of Spring annotations so it is unit-testable
  without a context.
- The validator in `rules/` must never import the solver. It is the independent
  oracle that proves the solver correct; shared code would let one bug hide another.

## Commands
./gradlew test          # all tests
./gradlew bootRun       # needs `docker compose up -d db` first