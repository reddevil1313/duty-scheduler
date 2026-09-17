package com.dutyscheduler.duty.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface AbsenceRepository extends JpaRepository<AbsenceEntity, Long> {

    /**
     * Every booking that could possibly touch a sheet.
     *
     * <p>Deliberately generous: it filters on dates, and whether an absence really
     * reaches a given sheet is settled by {@link com.dutyscheduler.duty.domain.Absence}
     * comparing intervals. The 2000 cut-off is a domain rule and it is not going
     * to be re-expressed in JPQL, where it would be a second definition free to
     * drift from the first.
     */
    @Query("""
            SELECT a FROM AbsenceEntity a
             WHERE a.toDate >= :from AND a.fromDate <= :to
             ORDER BY a.fromDate
            """)
    List<AbsenceEntity> findTouching(@Param("from") LocalDate from, @Param("to") LocalDate to);

    List<AbsenceEntity> findByTrooperNameOrderByFromDateDesc(String name);
}
