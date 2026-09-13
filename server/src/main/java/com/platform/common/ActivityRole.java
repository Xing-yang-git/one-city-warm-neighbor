package com.platform.common;

/**
 * 用户活动角色常量类 — 用户在借用/互助中的身份视角，用于「我的记录」与「进行中」列表的角色区分。
 *
 * <p>字符串值与 C端 {@code miniprogram/utils/constants.js}、B端 {@code admin/src/utils/constants.ts}
 * 的对应常量严格一致，同时作为接口参数契约（{@code /api/user-activity} 的 {@code role} 参数），
 * 禁止修改取值。</p>
 *
 * <p>取值分两族，分别对应接口中的两个参数（勿混用）：
 * <ul>
 *   <li><b>type 族</b>（待审批列表 {@code getApprovals} 的 {@code type} 参数）：BORROW / LEND / HELP</li>
 *   <li><b>role 族</b>（进行中/已完成列表 {@code getInProgress}/{@code getCompleted} 的 {@code role} 参数）：
 *       BORROW / LEND / HELP_REQ / HELP_PRO</li>
 * </ul></p>
 *
 * <ul>
 *   <li>BORROW — 借入方视角：我向邻居借了东西</li>
 *   <li>LEND — 借出方视角：我把闲置借给了邻居</li>
 *   <li>HELP — 互助事项整体（仅 type 族使用，对应求助/帮忙两个方向的合集）</li>
 *   <li>HELP_REQ — 求助方视角：我发起了求助</li>
 *   <li>HELP_PRO — 帮忙方视角：我承接并帮助了邻居</li>
 * </ul>
 *
 * <p>注意与 {@link PostType} 的区分：{@code PostType} 描述「发布内容的类型」（LEND/WANTED/HELP），
 * 本类描述「当前用户在记录中的角色」，二者取值域与大小写风格均不同（前者大写下划线，后者小驼峰）。</p>
 */
public final class ActivityRole {

    /** 工具类，禁止实例化 */
    private ActivityRole() {
    }

    /** 借入方 — 我向邻居借入物品（对应求借发布中的需求方） */
    public static final String BORROW = "borrow";

    /** 借出方 — 我把闲置物品借给邻居（对应出借发布中的物品方） */
    public static final String LEND = "lend";

    /** 互助事项整体 — 求助与帮忙两个方向的合集，仅用于待审批列表的 type 参数 */
    public static final String HELP = "help";

    /** 求助方 — 我发起互助求助 */
    public static final String HELP_REQ = "helpReq";

    /** 帮忙方 — 我承接并完成了邻居的求助 */
    public static final String HELP_PRO = "helpPro";
}
