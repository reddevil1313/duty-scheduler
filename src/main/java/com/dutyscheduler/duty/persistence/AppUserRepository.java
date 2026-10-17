package com.dutyscheduler.duty.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Accounts. Not team-scoped by Hibernate (see {@link AppUserEntity}), so the
 * lookups a team admin uses all name the team.
 */
@Repository
public interface AppUserRepository extends JpaRepository<AppUserEntity, Long> {
    Optional<AppUserEntity> findByUsername(String username);

    /** Usernames are unique server-wide. */
    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByRole(String role);

    /** A team's members, admins first and then by name. */
    List<AppUserEntity> findByTeamIdOrderByRoleAscUsernameAsc(Long teamId);

    Optional<AppUserEntity> findByIdAndTeamId(Long id, Long teamId);

    long countByTeamIdAndRole(Long teamId, String role);

    long countByTeamId(Long teamId);
}
