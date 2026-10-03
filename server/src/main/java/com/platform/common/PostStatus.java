package com.platform.common;

/**
 * 帖子（内容）状态常量 — idle_items.status 与 help_requests.status 字段的唯一合法取值。
 *
 * <p>闲置物品与求助信息共用同一套内容生命周期状态（都可被申请、进行中、完成、下架），
 * 故归入同一常量类。与 C端 miniprogram/utils/constants.js 的 POST_STATUS、
 * B端 admin/src/utils/constants.ts 中对应常量保持一致。</p>
 *
 * <p><b>与相邻概念的区别</b>：
 * <ul>
 *   <li>{@code pending_review}（待 AI 审核）与 {@code pending}（已被申请、待审批）
 *       是两个不同阶段，前者是发布后的审核闸门，后者是审核通过上线后被别人申请；</li>
 *   <li>本类只管内容自身的状态，AI 审核流转另见 {@link ModerationStatus}；</li>
 *   <li>知识库条目的 online/offline 取值相同但业务域不同，见 {@link KnowledgeStatus}。</li>
 * </ul>
 *
 * <p>典型流转：发布 → {@code pending_review} → 审核通过 {@code online} →
 * 被申请 {@code pending} → 申请通过 {@code active} → 完成 {@code completed}；
 * 用户下架 {@code draft}，管理员下架 / AI 驳回 {@code offline}。</p>
 */
public final class PostStatus {

    /** 工具类，禁止实例化 */
    private PostStatus() {
    }

    /** 上架展示中 */
    public static final String ONLINE = "online";

    /** 已下架（管理员下架 / AI 驳回 / 删除草稿的终态） */
    public static final String OFFLINE = "offline";

    /** 草稿（用户自行下架后的中间态，C端显示为"已下架"） */
    public static final String DRAFT = "draft";

    /** 待 AI 审核（内容发布后先挂起，等待异步审核结果） */
    public static final String PENDING_REVIEW = "pending_review";

    /** 待审批（内容已上线后被住户申请，等待物主/求助人处理） */
    public static final String PENDING = "pending";

    /** 进行中（申请已通过，借用/帮助正在履行） */
    public static final String ACTIVE = "active";

    /** 已完成（借用已归还 / 帮助已完成） */
    public static final String COMPLETED = "completed";
}
