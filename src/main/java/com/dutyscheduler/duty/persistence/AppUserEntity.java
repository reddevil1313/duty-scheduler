package com.dutyscheduler.duty.persistence;

import jakarta.persistence.*;

/**
 * An account. The password column holds a bcrypt hash and nothing else.
 *
 * <p>
 * Not scoped by {@code @TenantId}: signing in has to find an account before
 * anyone knows its team, and the owner's has none. The team is a plain column,
 * and the code that lists a team's members filters on it by hand.
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
