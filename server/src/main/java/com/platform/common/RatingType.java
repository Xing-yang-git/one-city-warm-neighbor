package com.platform.common;

/**
 * 评价类型常量类 — {@code RatingRequest.ratingType} 字段的唯一合法取值。
 *
 * <p>字符串值与 C端 {@code miniprogram/utils/constants.js}、B端 {@code admin/src/utils/constants.ts}
 * 的对应常量严格一致，作为前后端契约，禁止修改取值。</p>
 *
 * <p>注意与 {@link ActivityRole} 的区分：两者取值有交集（均为 {@code "borrow"} / {@code "help"}），
 * 但语义不同——本类描述「这条评价针对哪类互助行为」，{@code ActivityRole} 描述「当前用户在记录中的角色视角」。
 * 二者不可互相引用，否则一方取值变更会静默影响另一方。</p>
 */
public final class RatingType {

    /** 工具类，禁止实例化 */
    private RatingType() {
    }

    /** 借用评价 — 针对闲置物品借用行为提交的评价 */
    public static final String BORROW = "borrow";

    /** 互助评价 — 针对技能互助/求助行为提交的评价 */
    public static final String HELP = "help";
}
