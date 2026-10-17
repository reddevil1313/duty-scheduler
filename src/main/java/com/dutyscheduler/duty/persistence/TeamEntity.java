package com.dutyscheduler.duty.persistence;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A team: its own roster, sheets, leave and records, which no other team sees.
 * Not itself scoped to a team — the owner lists them all — so it carries no
 * {@code @TenantId}.
 */
@Entity
@Table(name = "team")
public class TeamEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Set here rather than by the column default, so a new row has it before it is read back. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TeamEntity() {
    }

    public TeamEntity(String name) {
        this.name = name;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
