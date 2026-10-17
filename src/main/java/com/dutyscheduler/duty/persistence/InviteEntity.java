package com.dutyscheduler.duty.persistence;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A one-time link to join a team in a role. Holds the token's hash, never the
 * token (V7).
 *
 * <p>
 * Not scoped by {@code @TenantId}: the person accepting is signed in to no
 * team yet, and the owner makes invites for teams that are not theirs. The
 * team is a plain column, and every admin-facing query names it.
 */
@Entity
@Table(name = "invite")
public class InviteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false, updatable = false)
    private Long teamId;

    @Column(nullable = false, updatable = false)
    private String role;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    /** Set here rather than by the column default, so a new row has it before it is read back. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "used_by")
    private String usedBy;

    protected InviteEntity() {
    }

    public InviteEntity(Long teamId, String role, String tokenHash, String createdBy, Instant expiresAt) {
        this.teamId = teamId;
        this.role = role;
        this.tokenHash = tokenHash;
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getTeamId() {
        return teamId;
    }

    public String getRole() {
        return role;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    /** Neither used nor expired. A revoked invite is deleted, so it is not found at all. */
    public boolean isOpen(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }
}
