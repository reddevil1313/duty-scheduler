package com.dutyscheduler.duty.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeamRepository extends JpaRepository<TeamEntity, Long> {

    /** Matched as the unique index matches, ignoring case. */
    Optional<TeamEntity> findByNameIgnoreCase(String name);

    List<TeamEntity> findAllByOrderByNameAsc();
}
