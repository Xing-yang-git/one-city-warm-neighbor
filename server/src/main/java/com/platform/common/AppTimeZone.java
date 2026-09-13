package com.platform.common;

import java.time.ZoneId;

/**
 * 应用统一时区常量 — 全项目时间取值的唯一时区来源。
 *
 * <p>背景：{@code LocalDateTime.now()} 隐式依赖 JVM 默认时区（{@code ZoneId.systemDefault()}），
 * 部署环境时区变化或被显式指定（如 {@code -Duser.timezone}）时会静默改变所有落库时间，
 * 静态检查工具（SonarLint S8688）亦会告警「Explicitly specify the time zone」。</p>
 *
 * <p>因此全项目获取当前时间一律写为：
 * <pre>{@code
 * LocalDateTime now = LocalDateTime.now(AppTimeZone.APP_ZONE);
 * }</pre>
 * 禁止调用无参的 {@code LocalDateTime.now()}。</p>
 *
 * <p>取值与 Spring 配置的 {@code spring.jackson.time-zone: Asia/Shanghai} 保持一致，
 * 确保「写入数据库的时间」与「序列化给前端的时间」语义统一。</p>
 */
public final class AppTimeZone {

    /** 工具类，禁止实例化 */
    private AppTimeZone() {
    }

    /**
     * 应用统一时区（Asia/Shanghai）— 时间获取与序列化的唯一依据。
     */
    public static final ZoneId APP_ZONE = ZoneId.of("Asia/Shanghai");
}
