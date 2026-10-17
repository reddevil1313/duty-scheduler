package com.dutyscheduler.duty.persistence;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface AbsenceRepository extends JpaRepository<AbsenceEntity, Long> {

    /**
     * Bookings that might touch a sheet, by date only. The exact check is
     * {@link com.dutyscheduler.duty.domain.Absence#touches}, not duplicated in JPQL.
     */
    @Query("""
            SELECT a FROM AbsenceEntity a
             WHERE a.toDate >= :from AND a.fromDate <= :to
             ORDER BY a.fromDate
            """)
    List<AbsenceEntity> findTouching(@Param("from") LocalDate from, @Param("to") LocalDate to);

    // Fetch the Trooper too: open-in-view is off, so lazy loading would fail.
    @EntityGraph(attributePaths = "trooper")
    List<AbsenceEntity> findByTrooperNameOrderByFromDateDesc(String name);

    /** The whole register, latest first. Ties go to the most recently booked. */
    @EntityGraph(attributePaths = "trooper")
    List<AbsenceEntity> findAllByOrderByFromDateDescIdDesc();
}
