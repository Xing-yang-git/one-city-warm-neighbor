package com.platform.model.entity;

import com.platform.common.AppTimeZone;
import com.platform.model.entity.column.RatingsColumn;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 用户评价实体，对应 ratings 表。
 *
 * <p>借用归还后或帮助完成后，双方可互相评价。评分范围 1-5 星，可附带文字反馈。
 * 通过 borrowId 或 helpApplicationId 关联具体的借用记录或帮助申请。</p>
 */
@Entity
@Table(name = RatingsColumn.TABLE_NAME)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Rating {

    /** 评价 ID（自增主键） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联借用记录 ID，外键 → borrow_requests.id（与 helpApplicationId 互斥） */
    @Column(name = RatingsColumn.COL_BORROW_ID)
    private Long borrowId;

    /** 关联帮助申请 ID，外键 → help_applications.id（与 borrowId 互斥） */
    @Column(name = RatingsColumn.COL_HELP_APPLICATION_ID)
    private Long helpApplicationId;

    /** 评价方用户 ID，外键 → users.id */
    @Column(name = RatingsColumn.COL_FROM_USER_ID, nullable = false)
    private Long fromUserId;

    /** 被评价方用户 ID，外键 → users.id */
    @Column(name = RatingsColumn.COL_TO_USER_ID, nullable = false)
    private Long toUserId;

    /** 评分（1-5 星） */
    @Column(name = RatingsColumn.COL_SCORE, nullable = false)
    @Builder.Default
    private Integer score = 5;

    /** 评价反馈文字 */
    @Column(name = RatingsColumn.COL_FEEDBACK, length = 500)
    private String feedback;

    /** 创建时间 */
    @Column(name = RatingsColumn.COL_CREATED_AT, nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 关联借用记录实体（懒加载） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = RatingsColumn.COL_BORROW_ID, insertable = false, updatable = false)
    private BorrowRequest borrowRequest;

    /** 关联帮助申请实体（懒加载） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = RatingsColumn.COL_HELP_APPLICATION_ID, insertable = false, updatable = false)
    private HelpApplication helpApplication;

    /** 关联评价方用户实体（懒加载） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = RatingsColumn.COL_FROM_USER_ID, insertable = false, updatable = false)
    private User fromUser;

    /** 关联被评价方用户实体（懒加载） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = RatingsColumn.COL_TO_USER_ID, insertable = false, updatable = false)
    private User toUser;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(AppTimeZone.APP_ZONE);
    }
}
