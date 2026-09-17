package com.dutyscheduler.duty.persistence;

import com.dutyscheduler.duty.domain.Absence;
import com.dutyscheduler.duty.domain.AbsenceKind;
import com.dutyscheduler.duty.domain.AbsenceSpan;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalTime;

/** A leave booking. */
@Entity
@Table(name = "absence")
public class AbsenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trooper_id", nullable = false)
    private TrooperEntity trooper;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AbsenceKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AbsenceSpan span;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    private String note;

    @Column(name = "created_by")
    private String createdBy;

    protected AbsenceEntity() {
    }

    public AbsenceEntity(TrooperEntity trooper, Absence absence, String createdBy) {
        this.trooper = trooper;
        this.kind = absence.kind();
        this.span = absence.span();
        this.fromDate = absence.from();
        this.toDate = absence.to();
        this.startTime = absence.start();
        this.endTime = absence.end();
        this.createdBy = createdBy;
    }

    public Absence toDomain() {
        return new Absence(trooper.toDomain(), kind, span, fromDate, toDate, startTime, endTime);
    }

    public Long getId() {
        return id;
    }

    public TrooperEntity getTrooper() {
        return trooper;
    }

    public AbsenceKind getKind() {
        return kind;
    }

    public AbsenceSpan getSpan() {
        return span;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public String getNote() {
        return note;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
