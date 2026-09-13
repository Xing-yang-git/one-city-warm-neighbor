package com.platform.model.entity;

import com.platform.common.AppTimeZone;
import com.platform.common.SensitiveWordStatus;
import com.platform.model.entity.column.SensitiveWordsColumn;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 敏感词实体，对应 sensitive_word 表。
 *
 * <p>AI 对话输入前置过滤的词库，由 super_admin 在 B端管理。
 * 匹配服务在启动与增删改后加载启用词到内存缓存，文本与词库先归一化（全角转半角、
 * 转小写、繁转简、去空格/标点/emoji 干扰）再做包含匹配。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = SensitiveWordsColumn.TABLE_NAME)
public class SensitiveWord {

    /** 敏感词 ID（自增主键） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 敏感词原文（唯一，新增/编辑由 existsByWord 去重） */
    @Column(name = SensitiveWordsColumn.COL_WORD, nullable = false, length = 100, unique = true)
    private String word;

    /** 状态：ENABLED(启用)/DISABLED(停用)，引用 {@link SensitiveWordStatus}；仅启用词参与匹配 */
    @Enumerated(EnumType.STRING)
    @Column(name = SensitiveWordsColumn.COL_STATUS, nullable = false, length = 20)
    @Builder.Default
    private SensitiveWordStatus status = SensitiveWordStatus.ENABLED;

    /** 创建时间 */
    @Column(name = SensitiveWordsColumn.COL_CREATED_AT, nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = SensitiveWordsColumn.COL_UPDATED_AT, nullable = false)
    private LocalDateTime updatedAt;

    /** 插入前自动填充创建/更新时间 */
    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now(AppTimeZone.APP_ZONE);
        updatedAt = createdAt;
    }

    /** 更新前自动刷新更新时间 */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(AppTimeZone.APP_ZONE);
    }
}
