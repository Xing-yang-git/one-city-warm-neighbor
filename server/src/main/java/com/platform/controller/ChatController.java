package com.platform.controller;

import com.platform.common.Result;
import com.platform.model.dto.ChatHistoryDTO;
import com.platform.model.dto.ChatMessageDTO;
import com.platform.model.dto.ChatSessionDTO;
import com.platform.model.dto.ListDTO;
import com.platform.model.dto.SendMessageResponseDTO;
import com.platform.model.entity.Message;
import com.platform.security.LoginUser;
import com.platform.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 聊天消息 REST API — 发送消息、获取历史、撤回消息、会话列表。
 *
 * <p>C端用户之间的一对一聊天功能，通过 WebSocket 实时推送 + REST 持久化。
 * 消息状态流转：sent（已发送）→ delivered（已送达）→ read（已读）。
 * 支持消息撤回（2 分钟内），撤回后 content 置为 NULL。</p>
 */
@RestController
@RequestMapping("/api/chats")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 发送聊天消息 — 落库持久化 + WebSocket 推送。
     * 返回消息的数据库 id，供客户端替换乐观更新的临时 id。
     */
    @PostMapping("/send")
    public Result<SendMessageResponseDTO> send(@Valid @RequestBody Map<String, Object> body, Authentication auth) {
        Long fromUserId = ((LoginUser) auth.getPrincipal()).getUserId();
        Long toUserId = Long.valueOf(body.get("toUserId").toString());
        String content = (String) body.get("content");
        String messageType = (String) body.getOrDefault("messageType", "text");
        String sessionId = body.get("sessionId") != null ? body.get("sessionId").toString() : null;

        Message msg = chatService.sendMessage(fromUserId, toUserId, content, messageType, sessionId);
        return Result.ok(SendMessageResponseDTO.builder()
                .id(msg.getId())
                .createdAt(msg.getCreatedAt().toString())
                .build());
    }

    /**
     * 拉取会话历史（游标分页，倒序，最新在前）。
     * @param sessionId 会话标识
     * @param beforeId  上一页最后一条消息 id（首次加载不传）
     * @param size      每页条数，默认 30
     */
    @GetMapping("/history")
    public Result<ChatHistoryDTO> history(@RequestParam String sessionId,
                                          @RequestParam(required = false) Long beforeId,
                                          @RequestParam(defaultValue = "30") int size,
                                          Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();

        List<Message> messages = chatService.getHistory(sessionId, beforeId, Math.min(size, 100));

        boolean hasMore = messages.size() == size;
        Long oldestId = messages.isEmpty() ? null : messages.get(messages.size() - 1).getId();

        // 转为前端友好的 DTO 列表，屏蔽掉无权限查看的消息（仅发送/接收方可查看）
        List<ChatMessageDTO> list = messages.stream()
                .filter(m -> userId.equals(m.getFromUserId()) || userId.equals(m.getToUserId()))
                .map(this::toMessageDTO)
                .collect(Collectors.toList());

        return Result.ok(ChatHistoryDTO.builder()
                .messages(list)
                .hasMore(hasMore)
                .oldestId(oldestId)
                .build());
    }

    /**
     * 撤回消息 — 仅发送方可在 2 分钟内撤回。
     */
    @PostMapping("/recall/{id}")
    public Result<Void> recall(@PathVariable Long id, Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        chatService.recallMessage(id, userId);
        return Result.ok();
    }

    /**
     * 获取当前用户参与的全部会话摘要（消息列表页后端数据源）。
     * 消息页以本地存储为主、此接口为辅，弥补 WebSocket 断连时丢失的消息。
     */
    @GetMapping("/sessions")
    public Result<ListDTO<ChatSessionDTO>> sessions(Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new ListDTO<>(chatService.getUserSessions(userId)));
    }

    // —— 兼容旧版 /api/chat/relay 路径（过渡期保留，下个版本移除） ——
    @PostMapping("/relay")
    public Result<SendMessageResponseDTO> relay(@Valid @RequestBody Map<String, Object> body, Authentication auth) {
        return send(body, auth);
    }

    /** 保留旧版 relay 语义 */
    public void relayMessage(Long fromUserId, Long toUserId, String content, String messageType, String sessionId) {
        chatService.sendMessage(fromUserId, toUserId, content, messageType, sessionId);
    }

    // ============================================================
    // 序列化辅助
    // ============================================================

    private ChatMessageDTO toMessageDTO(Message m) {
        return ChatMessageDTO.builder()
                .id(m.getId())
                .sessionId(m.getSessionId())
                .fromUserId(m.getFromUserId())
                .toUserId(m.getToUserId())
                // 撤回后的消息不返回 content
                .content(m.getRecalledAt() != null ? null : m.getContent())
                .messageType(m.getMessageType())
                .status(m.getStatus())
                .recalledAt(m.getRecalledAt() != null ? m.getRecalledAt().toString() : null)
                .createdAt(m.getCreatedAt().toString())
                .build();
    }
}
