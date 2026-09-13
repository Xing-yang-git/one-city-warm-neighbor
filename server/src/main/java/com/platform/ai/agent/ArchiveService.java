package com.platform.ai.agent;

import com.platform.common.AgentConversationStatus;
import com.platform.common.AgentMessageRole;
import com.platform.common.AppTimeZone;
import com.platform.common.BizException;
import com.platform.model.entity.AgentConversation;
import com.platform.model.entity.AgentMessage;
import com.platform.model.entity.AgentMemorySegment;
import com.platform.model.entity.User;
import com.platform.repository.AgentConversationRepository;
import com.platform.repository.AgentMessageRepository;
import com.platform.repository.AgentMemorySegmentRepository;
import com.platform.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 会话归档服务 — PG 长期记忆（Redis 热会话滑动窗口 → 归档 → 恢复/软删 + 记忆压缩触发）。
 *
 * <p>滑动窗口设计：每次归档创建<b>一条新归档行</b>，同一会话的多条归档行通过会话级 conversation_id
 * 关联（首次归档行的 conversation_id = 自身 id 充当会话级 id，写回 session.conversationId；后续归档沿用）。
 * 归档是纯数据库搬运（同步路径零 LLM 调用），标题由压缩流程异步回填，压缩在专用线程池异步触发不等待。</p>
 *
 * <p>三个归档入口：消息数达阈值（archiveWindow，最旧 N 条）/ 空闲超时（archiveRemaining）/ 主动结束
 * （archiveRemaining：清空指令、exit 接口）。</p>
 */
@Slf4j
@Service
public class ArchiveService {

    private final SessionService sessionService;
    private final AgentConversationRepository conversationRepository;
    private final AgentMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final AgentMemorySegmentRepository memorySegmentRepository;
    private final MemoryCompressionService memoryCompressionService;

    /** 恢复会话时回填的最近轮数 */
    @Value("${ai.agent.max-turns:10}")
    private int resumeTurns;

    /** transcript 中用户行前缀（供压缩服务拼 transcript 与标题兜底，两处约定一致） */
    private static final String TRANSCRIPT_USER_PREFIX = "用户：";

    /** transcript 中助手行前缀 */
    private static final String TRANSCRIPT_ASSISTANT_PREFIX = "小邻：";

    /** 归档初始标题最大长度（字符）：压缩流程回填 LLM 优化标题前，用首条用户消息摘要兜底展示 */
    private static final int INITIAL_TITLE_MAX_LEN = 20;

    /**
     * 构造器注入。
     *
     * @param sessionService            热会话服务
     * @param conversationRepository    归档会话仓储
     * @param messageRepository         归档消息仓储
     * @param userRepository            用户仓储（取 tenantId）
     * @param memorySegmentRepository   记忆压缩段仓储（会话软删联动清理）
     * @param memoryCompressionService  记忆压缩服务（归档后异步触发压缩）
     */
    public ArchiveService(SessionService sessionService,
                          AgentConversationRepository conversationRepository,
                          AgentMessageRepository messageRepository,
                          UserRepository userRepository,
                          AgentMemorySegmentRepository memorySegmentRepository,
                          MemoryCompressionService memoryCompressionService) {
        this.sessionService = sessionService;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.memorySegmentRepository = memorySegmentRepository;
        this.memoryCompressionService = memoryCompressionService;
    }

    /**
     * 【已废弃】归档 Redis 热会话全部消息到 PG（兼容旧调用；等价于 {@link #archiveRemaining}）。
     *
     * <p>保留仅为兼容历史测试/调用方，新代码请用 archiveWindow / archiveRemaining。</p>
     *
     * @param userId 住户用户 ID
     * @deprecated 用 {@link #archiveRemaining(Long)} 替代
     */
    @Deprecated
    @Transactional
    public void archive(Long userId) {
        archiveRemaining(userId);
    }

    /**
     * 滑动窗口归档：把会话中新增部分（已归档回填前缀之外）最旧的 windowSize 条消息归档为一条新行，归档后从 Redis 移走。
     *
     * <p>resume 回填的消息已持久化在 PG（archivedPrefixCount 标记），此处只归档其后新增的消息，
     * 回填前缀不重复落库（避免历史消息条数虚高、出现重复问答）。归档行 title 暂空（异步回填）、
     * message_count/last_message_at 正确填充；归档后触发异步压缩，滑动窗口保留剩余上下文。</p>
     *
     * @param userId     住户用户 ID
     * @param windowSize 本次归档的新增消息条数（不足则全部归档；无新增返回 null）
     * @return 归档行 id；无会话/无消息/无新增/未绑定小区时返回 null
     */
    @Transactional
    public Long archiveWindow(Long userId, int windowSize) {
        AgentSession session = sessionService.getSession(userId);
        if (session == null || session.getMessages().isEmpty()) {
            return null;
        }
        int prefix = session.getArchivedPrefixCount();
        int unarchived = session.getMessages().size() - prefix;
        if (unarchived <= 0) {
            // 热会话内全是已归档回填消息（无新增）：不重复归档建行
            return null;
        }
        int count = Math.min(Math.max(windowSize, 1), unarchived);
        // 只归档新增部分的最旧 count 条；已归档回填前缀不重复落库
        List<AgentSession.AgentMessageItem> window = new ArrayList<>(session.getMessages().subList(prefix, prefix + count));
        // 保留部分 = 已归档回填前缀 + 剩余新增（前缀保留作会话继续的上下文，计数不变）
        List<AgentSession.AgentMessageItem> keep = new ArrayList<>(session.getMessages().subList(0, prefix));
        keep.addAll(session.getMessages().subList(prefix + count, session.getMessages().size()));
        session.setArchivedPrefixCount(prefix);
        return doArchive(userId, session, window, keep);
    }

    /**
     * 剩余全部归档：把会话中新增部分（已归档回填前缀之外）的剩余消息归档为一条新行 + 触发压缩。
     *
     * <p>会话结束用（清空指令 / exit 接口 / 空闲兜底 / resume 切走当前会话）；无消息或无新增时不建行。
     * resume 回填的消息已在 PG（archivedPrefixCount 标记），只归档其后新增，回填前缀不重复落库。</p>
     *
     * @param userId 住户用户 ID
     * @return 归档行 id；无会话/无消息/无新增/未绑定小区时返回 null
     */
    @Transactional
    public Long archiveRemaining(Long userId) {
        AgentSession session = sessionService.getSession(userId);
        if (session == null || session.getMessages().isEmpty()) {
            return null;
        }
        int prefix = session.getArchivedPrefixCount();
        int unarchived = session.getMessages().size() - prefix;
        if (unarchived <= 0) {
            // 热会话内仅剩已归档回填消息（上次 resume 的回填，无新增）：不重复归档建行，
            // 按会话结束语义清理热会话（回填消息已在 PG，清理不丢数据）
            session.setMessages(new ArrayList<>());
            session.setConversationId(null);
            session.setArchivedPrefixCount(0);
            sessionService.saveSession(userId, session);
            return null;
        }
        // 只归档新增部分；已归档回填前缀不重复落库
        List<AgentSession.AgentMessageItem> all = new ArrayList<>(session.getMessages().subList(prefix, session.getMessages().size()));
        session.setArchivedPrefixCount(0);
        Long archived = doArchive(userId, session, all, List.of());
        // 会话结束语义：归档全部消息后清空会话级 conversationId——
        // 否则退出/清空后开启的新会话沿用旧 id，归档时多个会话被合并到同一 conversation_id（历史被合并、条数虚高）
        session.setConversationId(null);
        sessionService.saveSession(userId, session);
        return archived;
    }

    /**
     * 归档核心流程（纯数据库搬运，同步路径零 LLM 调用）：新建一行 → 写消息 → 移走已归档消息 → 异步触发压缩。
     *
     * @param userId   住户用户 ID
     * @param session  热会话（归档后原地更新 messages）
     * @param toArchive 本次归档的消息列表（按会话顺序）
     * @param keep     归档后保留在 Redis 的消息列表（剩余窗口）
     * @return 归档行 id；未绑定小区时返回 null
     */
    private Long doArchive(Long userId, AgentSession session,
                           List<AgentSession.AgentMessageItem> toArchive,
                           List<AgentSession.AgentMessageItem> keep) {
        if (toArchive.isEmpty()) {
            return null;
        }
        User user = userRepository.findById(userId).orElse(null);
        Long tenantId = user != null ? user.getTenantId() : null;
        if (tenantId == null) {
            log.warn("会话归档跳过（用户未绑定小区，避免 NOT NULL 冲突）: userId={}", userId);
            return null;
        }

        // 初始标题：用本窗口首条用户消息摘要兜底——压缩流程异步回填 LLM 优化标题前，
        // 历史列表不显示"未命名对话"；会话仅含 AI/工具消息（无用户消息）时保持 null
        String initialTitle = toArchive.stream()
                .filter(item -> AgentMessageRole.USER.equals(item.role()))
                .map(AgentSession.AgentMessageItem::content)
                .filter(Objects::nonNull)
                .findFirst()
                .map(this::abbreviateTitle)
                .orElse(null);
        // 新建归档行（每窗口一条；标题由压缩流程异步回填优化，见 MemoryCompressionService#backfillConversationTitle）
        AgentConversation conversation = AgentConversation.builder()
                .userId(userId)
                .tenantId(tenantId)
                .title(initialTitle)
                .messageCount(toArchive.size())
                .status(AgentConversationStatus.ARCHIVED)
                .lastMessageAt(LocalDateTime.now(AppTimeZone.APP_ZONE))
                .build();
        conversation = conversationRepository.save(conversation);

        // 会话级 conversation_id：首次归档用新行自身 id 充当，并写回 session.conversationId；后续沿用
        Long sessionConversationId = session.getConversationId();
        if (sessionConversationId == null) {
            sessionConversationId = conversation.getId();
            session.setConversationId(sessionConversationId);
        }
        conversation.setConversationId(sessionConversationId);
        conversationRepository.save(conversation);

        // 批量写消息（saveAll 替代 N+1 逐条 save）
        List<AgentMessage> messages = new ArrayList<>();
        for (AgentSession.AgentMessageItem item : toArchive) {
            messages.add(AgentMessage.builder()
                    .conversationId(conversation.getId())
                    .role(item.role())
                    .content(item.content())
                    .sources(item.sources())
                    .actions(item.actions())
                    .build());
        }
        messageRepository.saveAll(messages);

        // 归档后移走已归档消息，保留剩余
        session.setMessages(keep);
        session.setLastActive(LocalDateTime.now(AppTimeZone.APP_ZONE));
        sessionService.saveSession(userId, session);

        // 异步触发压缩（不等待、不阻塞；失败在压缩服务内降级，绝不影响归档主链路）
        triggerCompression(userId, tenantId, sessionConversationId, conversation.getId(), toArchive);

        log.info("Agent 会话归档完成: userId={}, conversationId={}, archiveRowId={}, 归档 {} 条",
                userId, sessionConversationId, conversation.getId(), toArchive.size());
        return conversation.getId();
    }

    /**
     * 归档初始标题摘要：压缩流程回填 LLM 优化标题前的兜底展示——去空白、按 {@link #INITIAL_TITLE_MAX_LEN} 截断。
     *
     * @param content 用户消息原文
     * @return 摘要标题（不超过 INITIAL_TITLE_MAX_LEN 字符）
     */
    private String abbreviateTitle(String content) {
        String compact = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (compact.isEmpty()) {
            // 无有效标题内容（空/纯空白消息）→ null，历史列表走「未命名对话」兜底，避免空串标题
            return null;
        }
        return compact.length() <= INITIAL_TITLE_MAX_LEN
                ? compact
                // 按 Unicode 码点截断，避免 substring 切断 emoji 代理对产生乱码
                : compact.codePoints().limit(INITIAL_TITLE_MAX_LEN)
                        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                        .toString();
    }

    /**
     * 历史会话列表（排除软删，分页）— 按会话级 conversation_id 分组，每会话一条。
     *
     * <p>滑动窗口改造后同一会话多条归档行合并展示：id=会话级 id、messageCount=该会话归档消息总数、
     * updatedAt=该会话最新变更时间；标题取会话代表行（首次归档行 id==conversation_id，未回填则「未命名对话」）。</p>
     *
     * @param userId   住户用户 ID
     * @param pageable 分页参数
     * @return 历史会话分页（id/title/messageCount/status/updatedAt）
     */
    @Transactional(readOnly = true)
    public Page<Map<String, Object>> list(Long userId, Pageable pageable) {
        List<AgentConversation> rows = conversationRepository
                .findAllByUserIdAndStatusNot(userId, AgentConversationStatus.DELETED);
        // 当前正在进行的对话（热会话会话级 id）不出现在历史列表：避免「切换到自己」的无意义操作
        Long currentConversationId = currentConversationId(userId);
        // 按会话级 id 分组（conversation_id 为空的历史数据视作自身 id）
        Map<Long, List<AgentConversation>> groups = rows.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getConversationId() != null ? c.getConversationId() : c.getId(),
                        LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> sessions = new ArrayList<>();
        for (Map.Entry<Long, List<AgentConversation>> entry : groups.entrySet()) {
            Long sessionId = entry.getKey();
            // 跳过当前会话：热会话会话级 id 与归档会话一致即视为同一对话，不允许在历史里选中它
            if (currentConversationId != null && currentConversationId.equals(sessionId)) {
                continue;
            }
            List<AgentConversation> list = entry.getValue();
            AgentConversation rep = list.stream()
                    .filter(c -> sessionId.equals(c.getId()))
                    .findFirst()
                    .orElse(list.get(0));
            int totalCount = list.stream()
                    .mapToInt(c -> c.getMessageCount() == null ? 0 : c.getMessageCount())
                    .sum();
            LocalDateTime updatedAt = list.stream()
                    .map(c -> c.getUpdatedAt() != null ? c.getUpdatedAt() : c.getCreatedAt())
                    .filter(Objects::nonNull)
                    .max(LocalDateTime::compareTo)
                    .orElse(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", sessionId);
            row.put("title", rep.getTitle() == null ? "未命名对话" : rep.getTitle());
            row.put("messageCount", totalCount);
            row.put("status", rep.getStatus());
            row.put("updatedAt", updatedAt == null ? rep.getCreatedAt() : updatedAt);
            sessions.add(row);
        }
        // 按 updatedAt 倒序
        sessions.sort((a, b) -> {
            LocalDateTime ta = (LocalDateTime) a.get("updatedAt");
            LocalDateTime tb = (LocalDateTime) b.get("updatedAt");
            if (ta == null) {
                return tb == null ? 0 : 1;
            }
            if (tb == null) {
                return -1;
            }
            return tb.compareTo(ta);
        });
        int from = Math.min((int) pageable.getOffset(), sessions.size());
        int to = Math.min(from + pageable.getPageSize(), sessions.size());
        return new PageImpl<>(new ArrayList<>(sessions.subList(from, to)), pageable, sessions.size());
    }

    /**
     * 当前热会话的会话级 id（进行中的对话；无热会话或尚未归档过为 null）。
     *
     * <p>历史列表据此排除当前对话：当前对话被滑动窗口归档或从历史恢复后，其会话级 id 会同时存在于
     * 热会话与归档表——若不排除，用户可在历史弹窗中选中正在进行的对话（「切换到自己」）。</p>
     *
     * @param userId 住户用户 ID
     * @return 当前会话级 id，或 null
     */
    private Long currentConversationId(Long userId) {
        AgentSession session = sessionService.getSession(userId);
        return session != null ? session.getConversationId() : null;
    }

    /**
     * 恢复会话到 Redis 热会话（按会话级 conversation_id，该会话所有归档行消息全部回填最近 N 轮）。
     *
     * <p>回填量仍按 resumeTurns×2 上限；conversationId 保留会话级 id，继续对话沿用同一会话。
     * 返回回填的最近消息，供前端恢复后直接渲染对话内容。</p>
     *
     * @param userId         住户用户 ID
     * @param conversationId 会话级 id（历史数据传入归档行 id）
     * @return 该会话全部归档消息列表（供前端完整展示对话内容；热会话仅回填最近 N 轮，sources/actions 为 JSON 字符串或 null）
     */
    @Transactional
    public List<AgentSession.AgentMessageItem> resume(Long userId, Long conversationId) {
        // 先校验目标会话有效性（不存在/已删除直接拒绝），再归档当前热会话——
        // 若先归档后校验失败：DB 事务回滚使归档行消失，但 Redis 热会话已被清空（不回滚）导致消息丢失，
        // 且异步压缩段已在独立事务提交，残留为指向不存在归档行的孤儿记忆段
        List<AgentConversation> rows = conversationRepository
                .findOwnedByConversationIdOrId(userId, conversationId, conversationId);
        if (rows.isEmpty()) {
            throw new BizException("会话不存在或无权访问");
        }
        boolean deleted = rows.stream()
                .anyMatch(c -> AgentConversationStatus.DELETED.equals(c.getStatus()));
        if (deleted) {
            throw new BizException("会话已删除");
        }
        // 目标有效后再归档当前未归档热会话（会话结束语义），避免被下方 new AgentSession() 覆盖导致消息丢失
        archiveRemaining(userId);

        // 会话级 id 以归档行实际 conversation_id 为准（历史 NULL 数据视作自身 id），
        // 兼容前端误传非首行 id 的旧缓存场景，恢复后继续对话仍沿用同一会话
        Long sessionId = rows.get(0).getConversationId() != null
                ? rows.get(0).getConversationId()
                : rows.get(0).getId();

        // 读该会话所有归档行消息，合并后按 id 升序，回填最近 resumeTurns×2 条
        List<AgentMessage> allMessages = new ArrayList<>();
        for (AgentConversation row : rows) {
            allMessages.addAll(messageRepository.findByConversationIdOrderByIdAsc(row.getId()));
        }
        allMessages.sort(Comparator.comparing(AgentMessage::getId));
        List<AgentSession.AgentMessageItem> items = allMessages.stream()
                .map(m -> new AgentSession.AgentMessageItem(m.getRole(), m.getContent(), m.getSources(), m.getActions(),
                        m.getCreatedAt()))
                .collect(Collectors.toList());
        int start = Math.max(0, items.size() - resumeTurns * 2);
        List<AgentSession.AgentMessageItem> recent = new ArrayList<>(items.subList(start, items.size()));

        AgentSession session = new AgentSession();
        session.setConversationId(sessionId);
        session.setMessages(recent);
        // 回填消息全部来自归档表（已在 PG），标记为已归档回填前缀——后续归档只存新增部分，避免重复落库
        session.setArchivedPrefixCount(recent.size());
        session.setLastActive(LocalDateTime.now(AppTimeZone.APP_ZONE));
        sessionService.saveSession(userId, session);
        log.info("Agent 会话恢复: userId={}, conversationId={}, 回填 {} 条", userId, sessionId, recent.size());
        // 返回该会话全部归档消息供前端完整展示对话内容（热会话/LLM 上下文仍只保留最近 recent 轮）
        return items;
    }

    /**
     * 软删会话（批量，按会话级 conversation_id），保留审计；越权/不存在的 id 静默跳过。
     *
     * <p>置 DELETED 行 = 该会话全部归档行（conversation_id IN ids OR id IN ids，强制 userId 过滤防越权）；
     * 同时硬删这些会话的所有压缩段（衍生数据随会话清理，避免孤儿）。</p>
     *
     * @param userId          住户用户 ID
     * @param conversationIds 会话级 id 列表
     * @return 实际删除的会话数（按会话级去重）
     */
    @Transactional
    public int softDelete(Long userId, List<Long> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return 0;
        }
        List<Long> ids = conversationIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return 0;
        }
        // 查询归属该用户的会话行（强制 userId 过滤，防越权删除他人会话）
        List<AgentConversation> owned = conversationRepository.findOwnedBySessionIds(userId, ids);
        if (owned.isEmpty()) {
            return 0;
        }
        Set<Long> sessionIds = owned.stream()
                .map(c -> c.getConversationId() != null ? c.getConversationId() : c.getId())
                .collect(Collectors.toSet());
        // 置 DELETED（保留审计）
        for (AgentConversation conversation : owned) {
            conversation.setStatus(AgentConversationStatus.DELETED);
        }
        conversationRepository.saveAll(owned);
        // 硬删这些会话的所有压缩段（会话归属已由上面 userId 过滤确认，避免孤儿衍生数据）
        List<AgentMemorySegment> segments = memorySegmentRepository.findByConversationIdIn(sessionIds);
        memorySegmentRepository.deleteAll(segments);
        log.info("Agent 会话软删: userId={}, 会话 {} 个，清理压缩段 {} 条",
                userId, sessionIds.size(), segments.size());
        return sessionIds.size();
    }

    /**
     * 异步触发记忆压缩（仅触发、不等待；任何异常仅记日志，绝不影响归档主链路）。
     *
     * @param userId         住户用户 ID
     * @param tenantId       所属小区 ID
     * @param conversationId 会话级 id
     * @param archiveRowId   归档行 id
     * @param messages       本次归档的消息列表（拼 transcript 用）
     */
    private void triggerCompression(Long userId, Long tenantId, Long conversationId, Long archiveRowId,
                                    List<AgentSession.AgentMessageItem> messages) {
        try {
            int used = memoryCompressionService.segmentNoOf(userId, conversationId);
            String transcript = buildTranscript(messages);
            memoryCompressionService.compressWindow(userId, tenantId, conversationId, archiveRowId,
                    transcript, used + 1);
        } catch (Exception e) {
            // 触发失败绝不影响归档主链路（归档已完成；压缩段可经会话结束时 compressRetry 补建）
            log.warn("记忆压缩触发失败（归档已完成，稍后可补压）: userId={}, conversationId={}, {}",
                    userId, conversationId, e.getMessage());
        }
    }

    /**
     * 由会话消息列表拼接 transcript（用户/助手角色分行，压缩服务约定同一前缀）。
     *
     * @param messages 归档窗口消息列表（按会话顺序）
     * @return 拼接文本（仅 user/assistant 角色）
     */
    private String buildTranscript(List<AgentSession.AgentMessageItem> messages) {
        StringBuilder sb = new StringBuilder();
        for (AgentSession.AgentMessageItem message : messages) {
            if (AgentMessageRole.USER.equals(message.role())) {
                sb.append(TRANSCRIPT_USER_PREFIX).append(message.content()).append('\n');
            } else if (AgentMessageRole.ASSISTANT.equals(message.role())
                    && message.content() != null && !message.content().isBlank()) {
                sb.append(TRANSCRIPT_ASSISTANT_PREFIX).append(message.content()).append('\n');
            }
        }
        return sb.toString();
    }
}
