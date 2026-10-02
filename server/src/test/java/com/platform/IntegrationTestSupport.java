package com.platform;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

/**
 * 集成测试公共支撑 —— 判断本地数据库是否可连通。
 *
 * <p>项目里绝大多数测试是纯 Mockito 单测，不碰数据库；只有少数集成测试需要真实 PostgreSQL
 * （验证行锁、条件更新等数据库语义）。若数据库没启动（例如 pgvector 容器停了），这些用例
 * 应当以「跳过」而非「失败」收场——否则 {@code mvn test} 会因环境问题误报失败，
 * 反而掩盖真正的代码缺陷。</p>
 *
 * <p><b>为什么用 {@code @EnabledIf} 而不是 {@code Assumptions}</b>：JUnit 的
 * {@code ExecutionCondition}（{@code @EnabledIf} 的实现机制）在 {@code BeforeAllCallback}
 * 之前求值，而 Spring 上下文是在 {@code BeforeAllCallback} 阶段创建的。因此连通性判断
 * 必须在这一步完成，否则数据库不可用时上下文创建会先一步抛错，跳过逻辑根本来不及生效。</p>
 */
public final class IntegrationTestSupport {

    /** 与 application.yml 的 spring.datasource 保持一致 */
    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/community_platform";
    private static final String DB_USER = "postgres";

    /** 连接超时（秒）：仅用于探测，不宜过长，否则拖慢跳过场景 */
    private static final int CONNECT_TIMEOUT_SECONDS = 3;

    private IntegrationTestSupport() {
    }

    /**
     * 本地 PostgreSQL 是否可连通（供 {@code @EnabledIf} 引用）。
     *
     * <p>密码取自环境变量 {@code DB_PASSWORD}（与后端启动方式一致）；未设置或连接失败
     * 一律视为不可用，集成测试跳过。</p>
     *
     * @return true 表示数据库可用，依赖数据库的集成测试应执行
     */
    public static boolean databaseReachable() {
        String password = System.getenv("DB_PASSWORD");
        if (password == null || password.isEmpty()) {
            System.out.println("[IntegrationTestSupport] 未设置 DB_PASSWORD，跳过依赖数据库的集成测试");
            return false;
        }
        Properties props = new Properties();
        props.setProperty("user", DB_USER);
        props.setProperty("password", password);
        props.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
        try (Connection ignored = DriverManager.getConnection(JDBC_URL, props)) {
            return true;
        } catch (Exception e) {
            System.out.println("[IntegrationTestSupport] 本地数据库不可达，跳过依赖数据库的集成测试：" + e.getMessage());
            return false;
        }
    }
}
