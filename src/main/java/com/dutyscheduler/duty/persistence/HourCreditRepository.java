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

    /** With sheet and Trooper fetched. */
    @EntityGraph(attributePaths = { "sheet", "trooper" })
    List<HourCreditEntity> findAllBy();

    @Modifying
    @Query("DELETE FROM HourCreditEntity c WHERE c.sheet = :sheet")
    void deleteBySheet(@Param("sheet") DutySheetEntity sheet);

    /** Like logged duty, this means archive instead of delete. */
    boolean existsByTrooper_Id(Long trooperId);
}
