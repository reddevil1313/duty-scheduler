package com.dutyscheduler.duty.persistence;

import com.dutyscheduler.duty.domain.Post;
import jakarta.persistence.*;

/**
 * One man, one hour, one post.
 *
 */
@Entity
@Table(name = "duty_assignment")
public class DutyAssignmentEntity {

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
    private short slot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Post post;

    protected DutyAssignmentEntity() {
    }

    public DutyAssignmentEntity(TrooperEntity trooper, int slot, Post post) {
        this.trooper = trooper;
        this.slot = (short) slot;
        this.post = post;
    }

    public Long getId() {
        return id;
    }

    public DutySheetEntity getSheet() {
        return sheet;
    }

    public TrooperEntity getTrooper() {
        return trooper;
    }

    public int getSlot() {
        return slot;
    }

    public Post getPost() {
        return post;
    }

    void setSheet(DutySheetEntity sheet) {
        this.sheet = sheet;
    }
}
