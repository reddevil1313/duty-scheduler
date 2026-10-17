package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.persistence.AppUserEntity;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * The signed-in account, as Spring Security holds it for the session.
 *
 * <p>
 * It carries the team, which is what every query is filtered by: the resolver
 * reads it from here on each request instead of looking the account up again.
 * The authority is {@code ROLE_ADMIN}, not {@code ADMIN}, because the role is
 * stored bare and checked with {@code hasRole}.
 *
 * <p>
 * The hash is needed once, to check the password, and dropped as soon as that
 * is done ({@link #eraseCredentials}), so it does not live in the session.
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
