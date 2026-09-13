package com.platform.common;

/**
 * 内容大类常量类 — 内容列表与「我的发布」中区分闲置与互助两大业务域。
 *
 * <p>对应 {@code ContentItemDTO.type} 与 {@code MyPostItemDTO.type} 字段的合法取值，
 * 字符串值与 C端 {@code miniprogram/utils/constants.js}、B端 {@code admin/src/utils/constants.ts}
 * 的对应常量一致，作为前后端契约，禁止修改取值。</p>
 *
 * <p><b>易混淆提示</b>：{@code RecordItemDTO.type} 字段取值域不同（{@code borrow} / {@code help}），
 * 对应的是 {@link ActivityRole} 而非本类——两者虽都含 {@code "help"}，但语义与取值集合均不同。</p>
 */
public final class ContentType {

    /** 工具类，禁止实例化 */
    private ContentType() {
    }

    /** 闲置 — 物品域内容（发布闲置、求借） */
    public static final String IDLE = "idle";

    /** 互助 — 求助域内容（发布求助、帮忙） */
    public static final String HELP = "help";
}
