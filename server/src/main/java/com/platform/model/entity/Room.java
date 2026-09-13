package com.platform.model.entity;

import com.platform.common.AppTimeZone;
import com.platform.model.entity.column.RoomsColumn;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 房间实体，对应 rooms 表。
 *
 * <p>单元下的最小空间划分，每个房间属于一个单元（unit）。
 * 用户通过 roomId 关联到具体的房间。房间号在同一单元内唯一。</p>
 */
@Entity
@Table(name = RoomsColumn.TABLE_NAME)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Room {

    /** 房间 ID（自增主键） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属单元 ID，外键 → units.id */
    @Column(name = RoomsColumn.COL_UNIT_ID, nullable = false)
    private Long unitId;

    /** 房间号（如 "1502"） */
    @Column(name = RoomsColumn.COL_ROOM_NUMBER, nullable = false, length = 10)
    private String roomNumber;

    /** 创建时间 */
    @Column(name = RoomsColumn.COL_CREATED_AT, nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 关联单元实体（懒加载） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = RoomsColumn.COL_UNIT_ID, insertable = false, updatable = false)
    private Unit unit;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(AppTimeZone.APP_ZONE);
    }
}
