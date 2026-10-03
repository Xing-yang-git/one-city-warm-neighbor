package com.platform.common;

/**
 * 物品成色常量 — idle_items.condition 字段的唯一合法取值。
 *
 * <p>描述发布时物品的新旧程度。与 C端 miniprogram/utils/constants.js 的 ITEM_CONDITION、
 * B端 admin/src/utils/constants.ts 中对应常量保持一致。</p>
 *
 * <p><b>与 {@link DamageType} 的区别</b>：两者都含 {@code normal} 这一取值，但业务域完全不同——
 * 本类是「发布时物品本来就多新」（like-new / normal / worn），
 * {@link DamageType} 是「借用归还时物品被损坏的程度」（normal / severe / broken）。
 * 同值不同域，不可互相引用。</p>
 */
public final class ItemCondition {

    /** 工具类，禁止实例化 */
    private ItemCondition() {
    }

    /** 几乎全新 */
    public static final String LIKE_NEW = "like-new";

    /** 正常使用痕迹（发布默认值） */
    public static final String NORMAL = "normal";

    /** 有明显磨损 */
    public static final String WORN = "worn";
}
