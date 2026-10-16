package com.dutyscheduler.duty.persistence;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HourCreditRepository extends JpaRepository<HourCreditEntity, Long> {

    /** Every credit, with its sheet and Trooper, for building the records in one query. */
    @EntityGraph(attributePaths = { "sheet", "trooper" })
    List<HourCreditEntity> findAllBy();

    @Modifying
    @Query("DELETE FROM HourCreditEntity c WHERE c.sheet = :sheet")
    void deleteBySheet(@Param("sheet") DutySheetEntity sheet);

    /** Whether this Trooper has any credited hours — like logged duty, it means archive, not delete. */
    boolean existsByTrooper_Id(Long trooperId);
}
