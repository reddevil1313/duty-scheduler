package com.dutyscheduler.duty.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Two views of the same table. The <em>roster</em> is the active Troopers — who
 * can be scheduled, booked on leave, or shown on the board. The plain lookups
 * also see archived Troopers, because a logged sheet still names them.
 */
@Repository
public interface TrooperRepository extends JpaRepository<TrooperEntity, Long> {

    /** Any Trooper with this name, archived or not. Names stay unique across both. */
    Optional<TrooperEntity> findByName(String name);

    /** Every Trooper ever added, archived included. */
    List<TrooperEntity> findAllByOrderByIdAsc();

    /** The roster: active Troopers, in the order they were added. */
    List<TrooperEntity> findByActiveTrueOrderByIdAsc();

    Optional<TrooperEntity> findByIdAndActiveTrue(Long id);

    Optional<TrooperEntity> findByNameAndActiveTrue(String name);

    List<TrooperEntity> findByOnNightTrue();
}
