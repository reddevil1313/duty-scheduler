package com.dutyscheduler.duty.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DutySheetRepository extends JpaRepository<DutySheetEntity, Long> {

  Optional<DutySheetEntity> findByDutyDate(LocalDate dutyDate);

  List<DutySheetEntity> findByDutyDateBetweenOrderByDutyDateDesc(LocalDate from, LocalDate to);

  boolean existsByDutyDate(LocalDate dutyDate);

  /**
   * Every logged sheet with its grid, latest first, in one query. The records
   * read them all; loading each sheet's assignments lazily would be one query
   * per day logged.
   */
  @Query("""
      SELECT DISTINCT s FROM DutySheetEntity s
        LEFT JOIN FETCH s.assignments a
        LEFT JOIN FETCH a.trooper
       ORDER BY s.dutyDate DESC
      """)
  List<DutySheetEntity> findAllWithAssignments();

  /**
   * Cumulative hours per man over a date range — what long-run fairness is
   * decided from.
   */
  @Query("""
      SELECT a.trooper.name, COUNT(a)
        FROM DutyAssignmentEntity a
       WHERE a.sheet.dutyDate BETWEEN :from AND :to
       GROUP BY a.trooper.name
      """)
  List<Object[]> hoursPerTrooper(@Param("from") LocalDate from, @Param("to") LocalDate to);

  /**
   * The same, restricted to the silent hours, for "who has done the most nights".
   */
  @Query("""
      SELECT a.trooper.name, COUNT(a)
        FROM DutyAssignmentEntity a
       WHERE a.sheet.dutyDate BETWEEN :from AND :to
         AND (a.slot <= 9 AND a.slot >= 2)
       GROUP BY a.trooper.name
      """)
  List<Object[]> silentHoursPerTrooper(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
