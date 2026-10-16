package com.dutyscheduler.duty.persistence;

import jakarta.persistence.*;

/** Hours credited to a Trooper for a logged day they did not stand. */
@Entity
@Table(name = "hour_credit")
public class HourCreditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sheet_id", nullable = false)
    private DutySheetEntity sheet;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trooper_id", nullable = false)
    private TrooperEntity trooper;

    @Column(nullable = false)
    private int hours;

    protected HourCreditEntity() {
    }

    public HourCreditEntity(DutySheetEntity sheet, TrooperEntity trooper, int hours) {
        this.sheet = sheet;
        this.trooper = trooper;
        this.hours = hours;
    }

    public DutySheetEntity getSheet() {
        return sheet;
    }

    public TrooperEntity getTrooper() {
        return trooper;
    }

    public int getHours() {
        return hours;
    }
}
