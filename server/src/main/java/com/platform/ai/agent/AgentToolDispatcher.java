package com.platform.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.ai.PolishingClient;
import com.platform.ai.common.PromptRepository;
import com.platform.ai.search.KnowledgeHit;
import com.platform.ai.search.KnowledgeRetrievalService;
import com.platform.common.BizStatus;
import com.platform.common.PostType;
import com.platform.model.dto.ApprovalCountDTO;
import com.platform.model.dto.BorrowResponseDTO;
import com.platform.model.dto.HelpResponseDTO;
import com.platform.model.dto.IdleItemDTO;
import com.platform.model.dto.MyPostItemDTO;
import com.platform.model.dto.NotificationDTO;
import com.platform.model.dto.PageDTO;
import com.platform.model.entity.User;
import com.platform.repository.UserRepository;
import com.platform.service.BorrowService;
import com.platform.service.HelpService;
import com.platform.service.IdleService;
import com.platform.service.NotificationService;
import com.platform.service.UserActivityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import java.util.Properties;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Agent 读工具执行器 — 搜索/查询/生成类工具的真实执行。
 *
 * <p><b>权限边界</b>：工具签名不接受 userId/tenantId 参数，userId 一律由
 * AgentService 从认证上下文注入（模型无法伪造他人身份查询）。</p>
 *
 * <p><b>请求级状态（key=requestId）</b>：工具调用计数（防死循环，上限 {@code ai.agent.max-tool-calls}）
 * 与知识检索命中缓存（供流结束取回引用来源）均由 AgentService 在请求开始 {@link #reset}、
 * 流结束 {@link #takeHits} 维护生命周期。</p>
 *
 * <p>仅注册读工具（可安全自动执行）；写操作走 IntentRouter JSON 意图出动作卡片，不在此执行。
 * 全部工具方法内部 catch 异常返回可读兜底文案，绝不把异常抛给模型（模块 4b 兜底核查）。
 * 每次成功调用输出工具调用日志（模块 4d，含 userId/工具名/关键词截断/命中条数）。</p>
 */
@Slf4j
@Component
public class AgentToolDispatcher {

    // ==================== 工具兜底文案常量 ====================

    /** 工具调用上限应答（模块 4a：达到 max-tool-calls 后不再执行）；文案来自 prompts/agent/replies.md */
    private final String toolLimitReply;
    /** 知识检索：无 tenantId（非业主/租客） */
    private final String kbNoPermissionReply;
    /** 知识检索：关键词去空白后为空或纯符号 */
    private final String kbInvalidKeywordReply;
    /** 知识检索：关键词为单字 */
    private final String kbKeywordTooShortReply;
    /** 知识检索：无命中 */
    private final String kbNoResultReply;
    /** 知识检索：内部异常 */
    private final String kbErrorReply;
    /** 日期解析失败 */
    private final String dateParseFailReply;
    /** 日期查询内部异常（防御性兜底，本地解析理论不抛） */
    private final String dateErrorReply;

    // ==================== 知识检索相关常量 ====================

    /** 检索关键词最长字符数（超长截断后检索） */
    private static final int KB_KEYWORD_MAX_LENGTH = 50;
    /** 工具调用日志中检索关键词截断字符数（防超长关键词刷爆日志，与 {@link #KB_KEYWORD_MAX_LENGTH} 同值） */
    private static final int TOOL_LOG_KEYWORD_MAX_LENGTH = 50;
    /** 返回给模型的知识命中条数上限 */
    private static final int KB_RESULT_MAX_COUNT = 3;
    /** 返回给模型的正文截断字符数 */
    private static final int KB_CONTENT_MAX_CHARS = 200;

    // ==================== 日期解析常量 ====================

    /** 日期输出格式（如 2026年8月8日） */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy年M月d日");
    /** 时间输出格式（如 2026-08-08 10:00） */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** N天前/N天后 匹配（N 为数字） */
    private static final Pattern DAYS_OFFSET_PATTERN = Pattern.compile("^(\\d+)天(前|后)$");
    /** 下周X/周X/星期X 匹配 */
    private static final Pattern WEEKDAY_PATTERN = Pattern.compile("^(下周|周|星期)([一二三四五六日天])$");
    /** 中文星期数 → 数字（1=周一 … 7=周日） */
    private static final Map<Character, Integer> WEEKDAY_NUM = Map.of(
            '一', 1, '二', 2, '三', 3, '四', 4, '五', 5, '六', 6, '日', 7, '天', 7);
    /** 中文星期名称（按 1=周一 索引） */
    private static final List<String> WEEKDAY_NAMES = List.of(
            "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日");

    private final IdleService idleService;
    private final BorrowService borrowService;
    private final PolishingClient polishingClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeRetrievalService retrievalService;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final HelpService helpService;
    private final UserActivityService userActivityService;

    /** 单轮对话工具调用次数上限（模块 4a，配置 ai.agent.max-tool-calls，默认 5） */
    @Value("${ai.agent.max-tool-calls:5}")
    private int maxToolCalls;

    /** 请求级知识命中缓存（key=requestId，并发安全容器；同一请求内多次调用合并去重） */
    private final Map<String, List<KnowledgeHit>> hitCache = new ConcurrentHashMap<>();

    /** 请求级工具调用计数（key=requestId，并发安全容器） */
    private final Map<String, AtomicInteger> toolCounts = new ConcurrentHashMap<>();

    /**
     * 构造器注入。
     *
     * @param idleService            闲置物品业务
     * @param borrowService          借用业务
     * @param polishingClient        互助感想文本生成客户端
     * @param objectMapper           JSON 序列化（工具结果给模型）
     * @param retrievalService       知识库检索服务（search_knowledge 工具）
     * @param userRepository         用户仓储（查用户与租户，判断知识库使用权限）
     * @param notificationService    通知服务（query_notifications 工具）
     * @param helpService            互助服务（query_help_requests 工具）
     * @param userActivityService    用户动态服务（my_todos 工具）
     */
    public AgentToolDispatcher(IdleService idleService,
                               BorrowService borrowService,
                               PolishingClient polishingClient,
                               ObjectMapper objectMapper,
                               KnowledgeRetrievalService retrievalService,
                               UserRepository userRepository,
                               NotificationService notificationService,
                               HelpService helpService,
                               UserActivityService userActivityService,
                               PromptRepository promptRepository) {
        // 兜底文案统一从提示词仓库读取（prompts/agent/replies.md），改文案不动代码
        Properties replies = promptRepository.getProps("agent.replies");
        this.toolLimitReply = replies.getProperty("tool.limit", "");
        this.kbNoPermissionReply = replies.getProperty("kb.no-permission", "");
        this.kbInvalidKeywordReply = replies.getProperty("kb.invalid-keyword", "");
        this.kbKeywordTooShortReply = replies.getProperty("kb.keyword-too-short", "");
        this.kbNoResultReply = replies.getProperty("kb.no-result", "");
        this.kbErrorReply = replies.getProperty("kb.error", "");
        this.dateParseFailReply = replies.getProperty("date.parse-fail", "");
        this.dateErrorReply = replies.getProperty("date.error", "");
        this.idleService = idleService;
        this.borrowService = borrowService;
        this.polishingClient = polishingClient;
        this.objectMapper = objectMapper;
        this.retrievalService = retrievalService;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.helpService = helpService;
        this.userActivityService = userActivityService;
    }

    // ==================== 知识检索工具 ====================

    /**
     * search_knowledge — 检索小区知识库（工具专用检索：召回 → 重排 → 分数关卡）。
     *
     * <p>参数校验（防幻觉 §9）：关键词去空白后为空或纯符号 → 无效；单字 → 太短；超 50 字截断。
     * 权限：无 tenantId（非业主/租客）不可用。命中结果合并去重后存入 requestId 缓存供流结束取引用来源。</p>
     *
     * @param userId    当前用户 ID（认证注入）
     * @param requestId 请求 ID（命中缓存 key，AgentService 请求级生命周期管理）
     * @param p         检索关键词
     * @return 检索结果文本（格式见 {@link #formatKnowledgeResults}）或兜底文案，绝不抛异常
     */
    public String searchKnowledge(Long userId, String requestId, KnowledgeSearchParams p) {
        String limit = checkToolLimit(requestId);
        if (limit != null) {
            return limit;
        }
        try {
            // 参数校验：去空白后为空或纯符号 → 无效；单字 → 太短；超 50 字 → 截断再检索
            String raw = p == null ? null : p.keyword();
            String keyword = raw == null ? "" : raw.trim();
            String noSpace = keyword.replaceAll("\\s", "");
            if (noSpace.isEmpty() || noSpace.matches("\\p{P}+")) {
                return kbInvalidKeywordReply;
            }
            if (keyword.length() == 1) {
                return kbKeywordTooShortReply;
            }
            if (keyword.length() > KB_KEYWORD_MAX_LENGTH) {
                keyword = keyword.substring(0, KB_KEYWORD_MAX_LENGTH);
            }

            // 用户权限：无 tenantId（非业主/租客）不可使用小区知识库
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || user.getTenantId() == null) {
                return kbNoPermissionReply;
            }

            // 检索：召回 top-8（向量 ∪ 关键词）→ 必走重排 top-3 → 重排分数关卡过滤
            long searchStartMs = System.currentTimeMillis();
            List<KnowledgeHit> hits = retrievalService.searchForAgent(user.getTenantId(), keyword);
            // 知识检索耗时（embedding + 向量召回 + 重排），用于定位 RAG 阶段耗时；
            // 关键词为内容类数据，仅 DEBUG 记录避免日志堆积与敏感内容落盘
            log.debug("Agent 知识检索耗时: userId={}, 关键词={}, 检索耗时={}ms, 命中={}",
                    userId, keyword, System.currentTimeMillis() - searchStartMs, hits == null ? 0 : hits.size());
            if (hits == null || hits.isEmpty()) {
                logToolCall(userId, "search_knowledge", keyword, 0);
                return kbNoResultReply;
            }

            // 同一请求内多次调用命中合并去重后存入缓存（供流结束取回引用来源）
            mergeHits(requestId, hits);

            logToolCall(userId, "search_knowledge", keyword, hits.size());
            return formatKnowledgeResults(hits);
        } catch (Exception e) {
            log.warn("search_knowledge 工具执行失败: userId={}, {}", userId, e.getMessage());
            return kbErrorReply;
        }
    }

    // ==================== 常用业务工具 ====================

    /**
     * query_date — 查询日期和星期（本地计算，不调外部服务）。
     *
     * <p>支持 今天/明天/昨天/前天/后天/大后天、N天前/N天后（N 为数字）、下周X/周X/星期X；解析失败返回可读兜底。</p>
     *
     * @param userId    当前用户 ID
     * @param requestId 请求 ID
     * @param p         相对日期描述（如「明天」「前天」「下周三」）
     * @return 日期结果文本（如「明天是 2026年8月8日 星期六」）或兜底文案
     */
    public String queryDate(Long userId, String requestId, DateQueryParams p) {
        String limit = checkToolLimit(requestId);
        if (limit != null) {
            return limit;
        }
        try {
            String resolved = resolveDate(p == null ? null : p.expression());
            String result = resolved != null ? resolved : dateParseFailReply;
            logToolCall(userId, "query_date", p == null ? null : p.expression(), resolved != null ? 1 : 0);
            return result;
        } catch (Exception e) {
            // 兜底：本地日期解析异常（理论不抛，防御性保护工具契约「绝不抛异常给模型」）
            log.warn("query_date 工具执行失败: userId={}, {}", userId, e.getMessage());
            return dateErrorReply;
        }
    }

    /**
     * query_notifications — 查询小区最近的通知/公告（服务通知 + 未读数）。
     *
     * @param userId    当前用户 ID
     * @param requestId 请求 ID
     * @param p         无参数（VoidParams）
     * @return 最新通知列表文本（最多 5 条，含标题/内容截断/时间/是否已读）+ 未读数，无通知返回兜底
     */
    public String queryNotifications(Long userId, String requestId, VoidParams p) {
        String limit = checkToolLimit(requestId);
        if (limit != null) {
            return limit;
        }
        try {
            List<NotificationDTO> notifications = notificationService.getNotifications(userId);
            long unread = notificationService.getUnreadCount(userId);
            if (notifications == null || notifications.isEmpty()) {
                logToolCall(userId, "query_notifications", null, 0);
                return "目前没有新的小区通知";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("共 ").append(notifications.size()).append(" 条通知，未读 ").append(unread).append(" 条：\n");
            int idx = 1;
            for (NotificationDTO n : notifications) {
                if (idx > 5) {
                    break;
                }
                sb.append(idx).append(". ").append(nullToEmpty(n.getTitle()))
                        .append(" | 内容：").append(truncate(n.getContent(), 50))
                        .append(" | 时间：").append(formatTime(n.getCreatedAt()))
                        .append(" | ").append(Boolean.TRUE.equals(n.getIsRead()) ? "已读" : "未读").append("\n");
                idx++;
            }
            logToolCall(userId, "query_notifications", null, notifications.size());
            return sb.toString();
        } catch (Exception e) {
            log.warn("query_notifications 工具执行失败: userId={}, {}", userId, e.getMessage());
            return "查询小区通知暂不可用，请稍后再试";
        }
    }

    /**
     * query_help_requests — 查询小区里的互助求助（找人帮忙、技能求助等）。
     *
     * @param userId    当前用户 ID
     * @param requestId 请求 ID
     * @param p         关键词（可选，可为空）
     * @return 求助列表文本（标题/分类/是否紧急/时间/发起人房号，最多 5 条），无结果返回兜底
     */
    public String queryHelpRequests(Long userId, String requestId, HelpSearchParams p) {
        String limit = checkToolLimit(requestId);
        if (limit != null) {
            return limit;
        }
        try {
            String keyword = p == null ? null : p.keyword();
            PageDTO<HelpResponseDTO> page = helpService.search(userId, keyword, 0, 5);
            List<HelpResponseDTO> list = page == null ? List.of() : page.getContent();
            if (list.isEmpty()) {
                logToolCall(userId, "query_help_requests", keyword, 0);
                return "目前没有找到相关的互助求助";
            }
            StringBuilder sb = new StringBuilder("找到以下互助求助：\n");
            int idx = 1;
            for (HelpResponseDTO dto : list) {
                sb.append(idx).append(". 标题：").append(nullToEmpty(dto.getTitle()))
                        .append(" | 分类：").append(nullToEmpty(dto.getCategory()))
                        .append(" | 是否紧急：").append(Boolean.TRUE.equals(dto.getIsUrgent()) ? "是" : "否")
                        .append(" | 时间：").append(formatTime(dto.getCreatedAt()))
                        .append(" | 发起人房号：").append(nullToEmpty(dto.getUserRoom())).append("\n");
                idx++;
            }
            logToolCall(userId, "query_help_requests", keyword, list.size());
            return sb.toString();
        } catch (Exception e) {
            log.warn("query_help_requests 工具执行失败: userId={}, {}", userId, e.getMessage());
            return "查询互助求助暂不可用，请稍后再试";
        }
    }

    /**
     * my_todos — 查询我的待办进度（进行中的借用/互助 + 待审批的申请）。
     *
     * @param userId    当前用户 ID
     * @param requestId 请求 ID
     * @param p         无参数（VoidParams）
     * @return 待审批 + 进行中的数量与摘要文本，无待办返回兜底
     */
    public String myTodos(Long userId, String requestId, VoidParams p) {
        String limit = checkToolLimit(requestId);
        if (limit != null) {
            return limit;
        }
        try {
            ApprovalCountDTO counts = userActivityService.getApprovalCounts(userId);
            int borrow = counts.getBorrow();
            int lend = counts.getLend();
            int help = counts.getHelp();
            int totalApproval = counts.getTotal();

            List<MyPostItemDTO> inProgressBorrow = userActivityService.getInProgress(userId, "borrow");
            List<MyPostItemDTO> inProgressLend = userActivityService.getInProgress(userId, "lend");
            List<MyPostItemDTO> inProgressHelpReq = userActivityService.getInProgress(userId, "helpReq");
            List<MyPostItemDTO> inProgressHelpPro = userActivityService.getInProgress(userId, "helpPro");
            int totalInProgress = inProgressBorrow.size() + inProgressLend.size()
                    + inProgressHelpReq.size() + inProgressHelpPro.size();

            if (totalApproval == 0 && totalInProgress == 0) {
                logToolCall(userId, "my_todos", null, 0);
                return "目前没有待处理的事项";
            }

            List<MyPostItemDTO> allInProgress = new ArrayList<>();
            allInProgress.addAll(inProgressBorrow);
            allInProgress.addAll(inProgressLend);
            allInProgress.addAll(inProgressHelpReq);
            allInProgress.addAll(inProgressHelpPro);

            StringBuilder sb = new StringBuilder();
            sb.append("待审批 ").append(totalApproval)
                    .append(" 项（借入确认 ").append(borrow)
                    .append("、借出审批 ").append(lend)
                    .append("、帮助申请 ").append(help).append("）；");
            sb.append("进行中 ").append(totalInProgress).append(" 项：");
            int idx = 1;
            for (MyPostItemDTO dto : allInProgress) {
                sb.append(idx).append(". ").append(nullToEmpty(dto.getTitle())).append("；");
                idx++;
            }
            logToolCall(userId, "my_todos", null, totalApproval + totalInProgress);
            return sb.toString();
        } catch (Exception e) {
            log.warn("my_todos 工具执行失败: userId={}, {}", userId, e.getMessage());
            return "查询我的待办暂不可用，请稍后再试";
        }
    }

    // ==================== 既有读工具（补兜底 + 请求级计数） ====================

    /**
     * search_items — 搜索闲置物品（混合检索，租户隔离由 IdleService 保证）。
     *
     * @param userId    当前用户 ID（认证注入）
     * @param requestId 请求 ID
     * @param p         关键词 + 类型过滤
     * @return 物品摘要 JSON（id/title/description/category），失败返回可读兜底
     */
    public String searchItems(Long userId, String requestId, SearchParams p) {
        String limit = checkToolLimit(requestId);
        if (limit != null) {
            return limit;
        }
        try {
            String postType = normalizePostType(p.postType());
            var page = idleService.search(userId, p.keyword(), postType, 0, 5, "hybrid");
            List<Map<String, Object>> items = page.getContent().stream()
                    .map(d -> Map.<String, Object>of(
                            "id", d.getId(),
                            "title", d.getTitle(),
                            "description", d.getDescription() == null ? "" : d.getDescription(),
                            "category", d.getCategory() == null ? "" : d.getCategory()))
                    .collect(Collectors.toList());
            logToolCall(userId, "search_items", p.keyword(), items.size());
            return writeJson(items);
        } catch (Exception e) {
            log.warn("search_items 工具执行失败: userId={}, {}", userId, e.getMessage());
            return "搜索闲置物品暂不可用，请稍后再试";
        }
    }

    /**
     * my_posts — 查询我发布的物品列表。
     *
     * @param userId    当前用户 ID
     * @param requestId 请求 ID
     * @param p         类型过滤
     * @return 我的发布摘要 JSON，失败返回可读兜底
     */
    public String myPosts(Long userId, String requestId, MyPostsParams p) {
        String limit = checkToolLimit(requestId);
        if (limit != null) {
            return limit;
        }
        try {
            String postType = normalizePostType(p.postType());
            List<IdleItemDTO> posts = idleService.getMyPosts(userId, postType);
            List<Map<String, Object>> items = posts.stream()
                    .map(d -> Map.<String, Object>of(
                            "id", d.getId(),
                            "title", d.getTitle(),
                            "status", d.getStatus() == null ? "" : d.getStatus()))
                    .collect(Collectors.toList());
            logToolCall(userId, "my_posts", p.postType(), items.size());
            return writeJson(items);
        } catch (Exception e) {
            log.warn("my_posts 工具执行失败: userId={}, {}", userId, e.getMessage());
            return "查询我的发布暂不可用，请稍后再试";
        }
    }

    /**
     * my_borrows_due — 查询我进行中的借用（借入方视角）。
     *
     * @param userId    当前用户 ID
     * @param requestId 请求 ID
     * @return 进行中借用 JSON（物品标题 + 借用时长），失败返回可读兜底
     */
    public String myBorrowsDue(Long userId, String requestId, VoidParams p) {
        String limit = checkToolLimit(requestId);
        if (limit != null) {
            return limit;
        }
        try {
            List<BorrowResponseDTO> active = borrowService.getMyApplications(userId).stream()
                    // 运行时经 approveReject 批准后的借用状态是 approved（种子数据用 active 掩盖了该差异），
                    // 与 AdminService 的 ACTIVE || APPROVED 双状态兼容保持一致
                    .filter(b -> BizStatus.ACTIVE.equals(b.getStatus()) || BizStatus.APPROVED.equals(b.getStatus()))
                    .collect(Collectors.toList());
            List<Map<String, Object>> items = active.stream()
                    .map(b -> Map.<String, Object>of(
                            "idleId", b.getIdleId(),
                            "idleTitle", b.getIdleTitle(),
                            "durationDays", b.getDurationDays() == null ? 0 : b.getDurationDays(),
                            "status", b.getStatus()))
                    .collect(Collectors.toList());
            logToolCall(userId, "my_borrows_due", null, items.size());
            return writeJson(items);
        } catch (Exception e) {
            log.warn("my_borrows_due 工具执行失败: userId={}, {}", userId, e.getMessage());
            return "查询我的借用暂不可用，请稍后再试";
        }
    }

    /**
     * generate_feedback — 生成互助感想评价文本（复用 PolishingClient）。
     *
     * @param userId    当前用户 ID
     * @param requestId 请求 ID
     * @param p         角色 + 标题 + 背景
     * @return 生成的评价文本（JSON 字符串包裹便于模型引用），失败返回可读兜底
     */
    public String generateFeedback(Long userId, String requestId, FeedbackParams p) {
        String limit = checkToolLimit(requestId);
        if (limit != null) {
            return limit;
        }
        try {
            String role = (p.role() == null || p.role().isBlank()) ? PolishingClient.ROLE_LEND : p.role();
            String feedback = polishingClient.generateFeedback(role, p.itemTitle(), p.description());
            logToolCall(userId, "generate_feedback", p.itemTitle(), 1);
            return writeJson(Map.of("feedback", feedback));
        } catch (Exception e) {
            log.warn("generate_feedback 工具执行失败: userId={}, {}", userId, e.getMessage());
            return "生成互助感想暂不可用，请稍后再试";
        }
    }

    // ==================== 请求级状态管理 ====================

    /**
     * 取回并清除该 requestId 的知识命中缓存（供对话服务流结束时取引用来源）。
     *
     * @param requestId 请求 ID
     * @return 本次请求工具命中列表（未命中返回空列表）
     */
    public List<KnowledgeHit> takeHits(String requestId) {
        if (requestId == null) {
            return List.of();
        }
        List<KnowledgeHit> hits = hitCache.remove(requestId);
        return hits == null ? List.of() : hits;
    }

    /**
     * 清除该 requestId 的工具调用计数与命中缓存（请求开始时调用）。
     *
     * @param requestId 请求 ID
     */
    public void reset(String requestId) {
        if (requestId == null) {
            return;
        }
        toolCounts.remove(requestId);
        hitCache.remove(requestId);
    }

    /**
     * 工具调用计数关卡（模块 4a）：计数 ≥ maxToolCalls 返回上限文案，不再执行；未超限计数 +1。
     *
     * @param requestId 请求 ID（null 时跳过计数，兼容非请求上下文直接调用）
     * @return 达到上限时的可读文案，未超限返回 null
     */
    private String checkToolLimit(String requestId) {
        if (requestId == null) {
            return null;
        }
        AtomicInteger counter = toolCounts.computeIfAbsent(requestId, k -> new AtomicInteger());
        if (counter.get() >= maxToolCalls) {
            return toolLimitReply;
        }
        counter.incrementAndGet();
        return null;
    }

    /**
     * 记录工具调用日志（模块 4d）— 含业务上下文 userId；检索关键词截断 ≤50 字；禁止打印知识库命中正文。
     *
     * @param userId      当前用户 ID
     * @param tool        工具名（与注册名一致）
     * @param keyword     检索关键词/参数（可为 null，截断到 {@link #TOOL_LOG_KEYWORD_MAX_LENGTH} 字符）
     * @param resultCount 返回给模型的条数（单条结果类工具传 1，无命中传 0）
     */
    private void logToolCall(Long userId, String tool, String keyword, int resultCount) {
        String k = keyword == null ? "" : keyword;
        if (k.length() > TOOL_LOG_KEYWORD_MAX_LENGTH) {
            k = k.substring(0, TOOL_LOG_KEYWORD_MAX_LENGTH);
        }
        log.info("Agent 工具调用: userId={}, 工具={}, 关键词={}, 返回 {} 条", userId, tool, k, resultCount);
    }

    /**
     * 把本次检索命中按 id 合并去重后存入 requestId 缓存（同一请求内多次调用累加）。
     *
     * @param requestId 请求 ID
     * @param hits      本次命中列表
     */
    private void mergeHits(String requestId, List<KnowledgeHit> hits) {
        if (requestId == null || hits == null) {
            return;
        }
        hitCache.compute(requestId, (key, existing) -> {
            List<KnowledgeHit> merged = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            Set<Long> ids = merged.stream().map(KnowledgeHit::id).collect(Collectors.toSet());
            for (KnowledgeHit hit : hits) {
                if (ids.add(hit.id())) {
                    merged.add(hit);
                }
            }
            return merged;
        });
    }

    // ==================== 文本组装 ====================

    /**
     * 组装知识检索结果返回文本（最多 3 条，标题带《》书名号，正文截断 200 字）。
     *
     * @param hits 重排+分数过滤后的命中列表
     * @return 返回给模型的检索结果文本
     */
    private String formatKnowledgeResults(List<KnowledgeHit> hits) {
        StringBuilder sb = new StringBuilder("找到以下小区资料：\n");
        int idx = 1;
        for (KnowledgeHit hit : hits) {
            if (idx > KB_RESULT_MAX_COUNT) {
                break;
            }
            String title = nullToEmpty(hit.title());
            String source = hit.source() == null ? "小区资料" : hit.source();
            String category = nullToEmpty(hit.category());
            sb.append("第 ").append(idx).append(" 条 | 标题：《").append(title)
                    .append("》 | 来源：").append(source)
                    .append(" | 分类：").append(category).append("\n");
            sb.append("内容：").append(truncate(hit.content(), KB_CONTENT_MAX_CHARS)).append("\n");
            idx++;
        }
        return sb.toString();
    }

    /**
     * 解析相对日期描述并格式化为「表达是 yyyy年M月d日 星期X」。
     *
     * <p>支持 今天/明天/昨天/前天/后天/大后天、N天前/N天后（N 为数字）、下周X/周X/星期X；
     * 解析失败返回 null（由调用方输出兜底文案）。</p>
     *
     * @param expression 相对日期描述
     * @return 格式化日期文本，或 null（无法识别）
     */
    private String resolveDate(String expression) {
        if (expression == null) {
            return null;
        }
        String expr = expression.trim();
        if (expr.isEmpty()) {
            return null;
        }
        LocalDate today = LocalDate.now();
        LocalDate date = switch (expr) {
            case "今天" -> today;
            case "明天" -> today.plusDays(1);
            case "昨天" -> today.minusDays(1);
            case "前天" -> today.minusDays(2);
            case "后天" -> today.plusDays(2);
            case "大后天" -> today.plusDays(3);
            default -> null;
        };
        if (date == null) {
            Matcher m = DAYS_OFFSET_PATTERN.matcher(expr);
            if (m.matches()) {
                int n = Integer.parseInt(m.group(1));
                date = "前".equals(m.group(2)) ? today.minusDays(n) : today.plusDays(n);
            } else {
                Matcher wm = WEEKDAY_PATTERN.matcher(expr);
                if (wm.matches()) {
                    Integer target = WEEKDAY_NUM.get(wm.group(2).charAt(0));
                    if (target != null) {
                        int current = today.getDayOfWeek().getValue();
                        // 本周内最近的该星期（含今天）；「下周X」在此基础上 +7 天
                        int delta = (target - current + 7) % 7;
                        if ("下周".equals(wm.group(1))) {
                            delta += 7;
                        }
                        date = today.plusDays(delta);
                    }
                }
            }
        }
        if (date == null) {
            return null;
        }
        return expr + "是 " + date.format(DATE_FMT) + " " + WEEKDAY_NAMES.get(date.getDayOfWeek().getValue() - 1);
    }

    /**
     * 截断文本到 max 字符（超出补省略号）。
     *
     * @param text 文本
     * @param max  最大字符数
     * @return 截断后的文本
     */
    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) + "……" : text;
    }

    /** null 文本安全转空串 */
    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 格式化时间为「yyyy-MM-dd HH:mm」（null 返回空串） */
    private String formatTime(LocalDateTime time) {
        return time == null ? "" : time.format(TIME_FMT);
    }

    /** postType 中文别名 → 枚举值映射（模型可能传中文而非枚举，Spring AI record schema 无 enum 约束） */
    private static final Map<String, String> POST_TYPE_ALIAS = new HashMap<>();
    static {
        POST_TYPE_ALIAS.put("求借", PostType.WANTED);
        POST_TYPE_ALIAS.put("需求", PostType.WANTED);
        POST_TYPE_ALIAS.put("借入", PostType.WANTED);
        POST_TYPE_ALIAS.put("想要", PostType.WANTED);
        POST_TYPE_ALIAS.put("求助", PostType.HELP);
        POST_TYPE_ALIAS.put("帮忙", PostType.HELP);
    }

    /**
     * 归一化 postType 参数 — Spring AI 从 record 生成的 schema 无 enum 约束，
     * 模型可能传中文（"出借"/"求借"）而非枚举值，统一映射到 {@link PostType} 合法值。
     *
     * @param raw 模型传入的 postType（可为 null / 中文 / 英文枚举）
     * @return 合法 PostType 值，无法识别默认 LEND
     */
    private String normalizePostType(String raw) {
        if (raw == null || raw.isBlank()) {
            return PostType.LEND;
        }
        String trimmed = raw.trim();
        // 中文别名映射优先（"求借"/"求助"等）
        String mapped = POST_TYPE_ALIAS.get(trimmed);
        if (mapped != null) {
            return mapped;
        }
        // 英文枚举透传（WANTED/HELP 合法）；未知值（含"出借"/"借出"/LEND）默认 LEND
        if (PostType.WANTED.equals(trimmed) || PostType.HELP.equals(trimmed)) {
            return trimmed;
        }
        return PostType.LEND;
    }

    /**
     * 序列化为 JSON 字符串（工具结果给模型）。
     *
     * @param data 数据对象
     * @return JSON 字符串，失败返回空 JSON
     */
    private String writeJson(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("工具结果序列化失败: {}", e.getMessage());
            return "[]";
        }
    }
}
