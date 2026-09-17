package com.dutyscheduler.duty.persistence;

import com.dutyscheduler.duty.domain.Trooper;
import jakarta.persistence.*;

/**
 * The roster, as rows.
 */
@Entity
@Table(name = "trooper")
public class TrooperEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String rank;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "stay_out", nullable = false)
    private boolean stayOut;

    @Column(name = "perm_pac", nullable = false)
    private boolean permPac;

    @Column(name = "on_night", nullable = false)
    private boolean onNight;

    private String remark;

    protected TrooperEntity() {
    }

    public TrooperEntity(String name, boolean stayOut) {
        this.name = name;
        this.stayOut = stayOut;
    }

    /** The domain object this row stands for. */
    public Trooper toDomain() {
        return new Trooper(name, stayOut);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getRank() {
        return rank;
    }

    public String getFullName() {
        return fullName;
    }

    public boolean isStayOut() {
        return stayOut;
    }

    public boolean isPermPac() {
        return permPac;
    }

    public boolean isOnNight() {
        return onNight;
    }

    public String getRemark() {
        return remark;
    }

    public void setRank(String rank) {
        this.rank = rank;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public void setStayOut(boolean stayOut) {
        this.stayOut = stayOut;
    }

    public void setPermPac(boolean permPac) {
        this.permPac = permPac;
    }

    public void setOnNight(boolean onNight) {
        this.onNight = onNight;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
