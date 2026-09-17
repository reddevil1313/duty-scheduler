package com.dutyscheduler.duty.persistence;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** One duty sheet, and the assignments that make it up. */
@Entity
@Table(name = "duty_sheet")
public class DutySheetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "duty_date", nullable = false, unique = true)
    private LocalDate dutyDate;

    private String note;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private String createdBy;

    /**
     * Assignments are owned by the sheet: replacing a day's schedule replaces its
     * rows, and deleting the sheet deletes them.
     */
    @OneToMany(mappedBy = "sheet", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<DutyAssignmentEntity> assignments = new ArrayList<>();

    protected DutySheetEntity() {
    }

    public DutySheetEntity(LocalDate dutyDate, String createdBy) {
        this.dutyDate = dutyDate;
        this.createdBy = createdBy;
    }

    public void add(DutyAssignmentEntity assignment) {
        assignments.add(assignment);
        assignment.setSheet(this);
    }

    /** Both sides of the association, kept in step. */
    public void replaceAssignments(List<DutyAssignmentEntity> replacements) {
        assignments.clear();
        for (DutyAssignmentEntity replacement : replacements) {
            add(replacement);
        }
    }

    public Long getId() {
        return id;
    }

    public LocalDate getDutyDate() {
        return dutyDate;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public List<DutyAssignmentEntity> getAssignments() {
        return assignments;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
