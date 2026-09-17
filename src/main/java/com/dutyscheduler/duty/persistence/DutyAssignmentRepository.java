package com.dutyscheduler.duty.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public interface DutyAssignmentRepository extends JpaRepository<DutyAssignmentEntity, Long> {

    @Query("""
            SELECT COUNT(a) FROM DutyAssignmentEntity a
             WHERE a.sheet.dutyDate = :date
            """)
    long countForDate(@Param("date") LocalDate date);
}
