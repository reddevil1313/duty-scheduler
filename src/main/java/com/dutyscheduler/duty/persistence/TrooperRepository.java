package com.dutyscheduler.duty.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrooperRepository extends JpaRepository<TrooperEntity, Long> {

    Optional<TrooperEntity> findByName(String name);

    List<TrooperEntity> findAllByOrderByIdAsc();

    List<TrooperEntity> findByOnNightTrue();
}
