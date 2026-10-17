package com.dutyscheduler.duty.persistence;

import jakarta.persistence.*;

/**
 * An account; the password is a bcrypt hash. Not a {@code @TenantId} entity,
 * since sign-in happens before the team is known. Filter by team by hand.
 */
@Entity
@Table(name = "app_user")
public class AppUserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String role;

    /** The one team this account belongs to; null only for the owner (V6's CHECK). */
    @Column(name = "team_id")
    private Long teamId;

    protected AppUserEntity() {
    }

    public AppUserEntity(String username, String displayName, String passwordHash, String role, Long teamId) {
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = role;
        this.teamId = teamId;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRole() {
        return role;
    }

    public Long getTeamId() {
        return teamId;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
