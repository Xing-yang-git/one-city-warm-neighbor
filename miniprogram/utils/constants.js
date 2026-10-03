/**
 * 业务常量定义 — 状态与发布类型的唯一来源
 *
 * 与后端 com.platform.common 下的常量类（PostStatus / AuthStatus / BorrowStatus /
 * HelpApplicationStatus / ItemCondition / PostType）保持一致：
 * 这些字符串值即前后端通信的字面值（DTO 字段、URL 参数），不得随意改动。
 */

// ========== 帖子状态（与后端 PostStatus 对齐）==========
const POST_STATUS = {
  ONLINE: 'online',                     // 在线中
  DRAFT: 'draft',                       // 草稿（用户下架后的中间态，C端显示为"已下架"）
  OFFLINE: 'offline',                   // 已下架（管理员下架/AI驳回/用户删除草稿的终态）
  PENDING_REVIEW: 'pending_review',     // 审核中
  PENDING: 'pending',                   // 待审批（借用/帮助申请提交后，帖子标记为此状态）
  ACTIVE: 'active',                     // 进行中（借用/帮助进行中）
  COMPLETED: 'completed'                // 已完成
};

// ========== 帖子类型（与后端 PostType 一致）==========
const POST_TYPE = {
  LEND: 'LEND',       // 闲置借出
  WANTED: 'WANTED',   // 需求借入
  HELP: 'HELP'        // 技能求助
};

/**
 * 内容大类 — 区分闲置与互助两大业务域，用于内容列表与「我的发布」筛选。
 * 与后端 com.platform.common.ContentType 保持一致。
 * 注意与 ACTIVITY_ROLE 区分：后者描述「当前用户在记录中的角色视角」，取值集合不同。
 */
const CONTENT_TYPE = {
  IDLE: 'idle',  // 闲置（物品域）
  HELP: 'help'   // 互助（求助域）
};

/**
 * 用户活动角色 — 借用/互助中的身份视角，用于审批/进行中/已完成子 Tab 与记录类型。
 * 与后端 com.platform.common.ActivityRole 保持一致。
 * 取值分两族，勿混用：
 *   - type 族（审批子 Tab / 记录类型）：BORROW | LEND | HELP
 *   - role 族（进行中 / 已完成子 Tab）：BORROW | LEND | HELP_REQ | HELP_PRO
 */
const ACTIVITY_ROLE = {
  BORROW: 'borrow',    // 借入方视角（我向邻居借）
  LEND: 'lend',        // 借出方视角（我把闲置借出）
  HELP: 'help',        // 互助事项整体（仅 type 族用）
  HELP_REQ: 'helpReq', // 求助方视角（我发起求助）
  HELP_PRO: 'helpPro'  // 帮忙方视角（我承接帮助）
};

// ========== 账户审核状态（与后端 AuthStatus 对齐）==========
const AUTH_STATUS = {
  PENDING: 'pending',           // 待审核
  APPROVED: 'approved',         // 已通过
  REJECTED: 'rejected',         // 已驳回
  REGISTERING: 'registering',   // 注册中（注册资料未提交完成）
  BANNED: 'banned'              // 已封禁
};

// ========== 借用申请状态（与后端 BorrowStatus 对齐）==========
// 注：后端 BorrowStatus 另含 compat-only 的 ACTIVE('active')——历史遗留值、无写入方，
//     C端无读取点，故不定义
const BORROW_STATUS = {
  PENDING: 'pending',       // 待审批
  APPROVED: 'approved',     // 已同意
  REJECTED: 'rejected',     // 已拒绝
  RETURNED: 'returned',     // 已归还
  COMPLETED: 'completed'    // 已完成
};

// ========== 帮助申请状态（与后端 HelpApplicationStatus 对齐）==========
const HELP_APPLICATION_STATUS = {
  PENDING: 'pending',       // 待审批
  APPROVED: 'approved',     // 已同意
  REJECTED: 'rejected',     // 已拒绝
  COMPLETED: 'completed'    // 已完成
};

/**
 * 损坏类型 — borrow_requests.damage_type 字段的唯一合法取值。
 * 与后端 com.platform.common.DamageType 保持一致。
 */
const DAMAGE_TYPE = {
  NORMAL: 'normal',    // 正常损耗
  ABNORMAL: 'severe',  // 非正常损坏（数据库值保持 severe）
  BROKEN: 'broken'     // 完全损坏
};

/**
 * 物品成色 — idle_items.condition 字段的唯一合法取值。
 * 与后端 com.platform.common.ItemCondition 保持一致。
 * 注意与 DAMAGE_TYPE 区分：两者都含 normal，但前者是「发布时物品多新」，后者是「归还时损坏程度」。
 */
const ITEM_CONDITION = {
  LIKE_NEW: 'like-new',   // 几乎全新
  NORMAL: 'normal',       // 正常使用痕迹（发布默认值）
  WORN: 'worn'            // 有明显磨损
};

/**
 * 归还状态 — borrow_requests.return_status 字段的唯一合法取值。
 * 与后端 com.platform.common.ReturnStatus 保持一致。
 */
const RETURN_STATUS = {
  ON_TIME: 'ontime',           // 按时归还
  DELAYED: 'delayed',          // 逾期归还
  NOT_RETURNED: 'not_returned' // 未归还
};

/**
 * 通知类型 — notifications.type 字段的唯一合法取值。
 * 与后端 com.platform.common.NotificationType 保持一致。
 */
const NOTIFICATION_TYPE = {
  BORROW_REQUEST: 'borrow_request',                           // 有人申请借用我的物品
  BORROW_APPLICATION: 'borrow_application',                   // 我的借用申请待对方回应
  BORROW_RESULT: 'borrow_result',                             // 借用申请已同意/拒绝
  HELP_APPLICATION: 'help_application',                       // 有人愿意帮我的求助
  HELP_APPLICATION_SUBMITTED: 'help_application_submitted',   // 我的帮助申请待对方回应
  HELP_RESULT: 'help_result',                                 // 帮助流程结果
  HELP_APPROVED: 'help_approved',                             // 我的帮助申请已被接受
  HELP_REJECTED: 'help_rejected',                             // 我的帮助申请已被谢绝
  AUDIT_RESULT: 'audit_result',                               // 用户审核结果
  VIOLATION: 'violation',                                     // 违规处理
  RETURN_CONFIRM: 'return_confirm',                           // 归还确认
  NOTIFICATION: 'notification',                               // 通用通知
  MATCH_DEMAND: 'match_demand',                               // 供需匹配：有人需要你出借过的物品
  CONTENT_REJECTED: 'content_rejected',                       // AI 内容审核驳回
  CONTENT_APPROVED: 'content_approved'                        // AI 内容审核通过
};

/**
 * 评价类型 — rating 页与「我的发布」提交评价时区分评价针对的行为。
 * 与后端 com.platform.common.RatingType 保持一致。
 * 注意：取值与 ACTIVITY_ROLE 有交集但语义不同，不可互相引用。
 */
const RATING_TYPE = {
  BORROW: 'borrow',  // 借用评价（针对闲置借用行为）
  HELP: 'help'       // 互助评价（针对技能求助行为）
};

/**
 * AI 助手 SSE 事件类型 — 后端流式对话事件体 {"type": ..., "data": ...} 的 type 唯一合法取值。
 * 与后端 com.platform.common.AgentEventType 保持一致，改动需前后端同步。
 * 注意：SSE 的 event: 行与 JSON 的 type 字段同一个值，后端由枚举统一发出，此处按 type 分派即可。
 */
const AGENT_EVENT_TYPE = {
  START: 'start',       // 会话开始（assistant 页不处理，落到末尾 return false）
  ANSWER: 'answer',     // 回复文本分块
  SOURCES: 'sources',   // 引用来源列表
  ACTION: 'action',     // 动作卡片（写操作，需用户确认）
  REPLACE: 'replace',   // 整体替换当前气泡正文
  CLEAR: 'clear',       // 清空消息列表（/clear、/reset、「清除对话」）
  END: 'end',           // 会话结束
  ERROR: 'error'        // 错误信息
};

/** 时长单位 — idle_items.duration_unit / 发布意图 durationUnit 取值，对齐后端 DurationUnit（含按小时档） */
const DURATION_UNIT = {
  DAY: 'day',     // 按天
  HOUR: 'hour'    // 按小时（需求借入/借出可选档）
};

/**
 * 取货方式 — idle_items.pickup_method 取值，对齐后端 PickupMethod。
 * 取值以 db/schema.sql 的列注释与三端 UI 为准：self_pickup / both。
 */
const PICKUP_METHOD = {
  SELF_PICKUP: 'self_pickup',   // 需自提
  BOTH: 'both'                  // 自提或送上门
};

/**
 * 本地存储键 — 跨页面共享的 storage key 统一管理，改名安全。
 */
const STORAGE_KEY = {
  TOKEN: 'token',               // 登录 token（boot/登录页读写）
  USER_INFO: 'userInfo',        // 用户信息缓存（boot/登录页读写）
  AGENT_DRAFT: 'agent_draft',   // AI 助手动作卡片发布草稿（assistant 写入 → publish-idle 读取）
  HISTORY_FAB_POS: 'history_fab_pos',    // 小邻历史悬浮按钮位置（拖动记忆，跨会话保留）
  HOME_FAB_POS: 'home_fab_pos'           // 首页 + 悬浮按钮位置（拖动记忆，跨会话保留）
};

module.exports = {
  POST_STATUS,
  POST_TYPE,
  CONTENT_TYPE,
  ACTIVITY_ROLE,
  AUTH_STATUS,
  BORROW_STATUS,
  HELP_APPLICATION_STATUS,
  DAMAGE_TYPE,
  ITEM_CONDITION,
  RETURN_STATUS,
  NOTIFICATION_TYPE,
  RATING_TYPE,
  AGENT_EVENT_TYPE,
  DURATION_UNIT,
  PICKUP_METHOD,
  STORAGE_KEY
};
