package com.dutyscheduler.duty.persistence;

import jakarta.persistence.*;

import java.time.Instant;

/** A team. Not a {@code @TenantId} entity, since the owner lists them all. */
@Entity
@Table(name = "team")
public class TeamEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Set here so it isn't null before the row is read back. */
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
