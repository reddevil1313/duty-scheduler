package com.dutyscheduler.duty.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface InviteRepository extends JpaRepository<InviteEntity, Long> {

    Optional<InviteEntity> findByTokenHash(String tokenHash);

    /** A team's invites still waiting to be used, newest first. */
    List<InviteEntity> findByTeamIdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(Long teamId, Instant now);

    Optional<InviteEntity> findByIdAndTeamId(Long id, Long teamId);

    /**
     * Use the invite, if it is still open, in one statement: the check and the
     * write cannot be split by a second person accepting the same link.
     *
     * @return 1 if this call used it, 0 if it was already used or has expired
     */
    @Modifying
    @Query("""
            UPDATE InviteEntity i SET i.usedAt = :now, i.usedBy = :username
             WHERE i.id = :id AND i.usedAt IS NULL AND i.expiresAt > :now
            """)
    int use(@Param("id") Long id, @Param("username") String username, @Param("now") Instant now);
}
