package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.persistence.AppUserEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * The signed-in account held in the session. Carries the team id so the
 * tenant resolver needn't look it up. Authorities are {@code ROLE_*} since
 * checks use {@code hasRole}. The password hash is erased after sign-in.
 */
public final class Account implements UserDetails, CredentialsContainer {

    private final long id;
    private final String username;
    private final String role;
    private final Long teamId;
    private String passwordHash;

    private Account(long id, String username, String passwordHash, String role, Long teamId) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.teamId = teamId;
    }

    public static Account of(AppUserEntity user) {
        return new Account(user.getId(), user.getUsername(), user.getPasswordHash(), user.getRole(),
                user.getTeamId());
    }

    public long id() {
        return id;
    }

    public String role() {
        return role;
    }

    /** Null for the owner, who belongs to no team. */
    public Long teamId() {
        return teamId;
    }

    /** Throws when there is no team (the owner, or a mock user). */
    static long teamOf(Account account) {
        if (account == null || account.teamId == null) {
            throw new AccessDeniedException("Only a team's own accounts can do this.");
        }
        return account.teamId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}
