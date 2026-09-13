package com.platform.service;

import com.platform.common.BizException;
import com.platform.common.UserFormatter;
import com.platform.model.dto.ChatSessionDTO;
import com.platform.model.dto.WebSocketMessage;
import com.platform.model.entity.Message;
import com.platform.model.entity.User;
import com.platform.repository.MessageRepository;
import com.platform.repository.UserRepository;
import com.platform.websocket.ChatWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final ChatWebSocketHandler webSocketHandler;

    public ChatService(UserRepository userRepository, MessageRepository messageRepository,
                       ChatWebSocketHandler webSocketHandler) {
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.webSocketHandler = webSocketHandler;
    }

    /**
     * 发送消息：落库持久化，然后通过 WebSocket 推送给在线接收方。
     * sessionId 统一归一化为 USER_{minId}_{maxId}，确保同一对用户始终共享同一会话。
     * @return 持久化后的消息（含自增 id），供 Controller 返回给发送方做乐观更新替换。
     */
    public Message sendMessage(Long fromUserId, Long toUserId, String content, String messageType, String sessionId) {
        // 归一化会话 ID：无论从哪个帖子入口发起，同一对用户始终使用同一会话
        String normalizedSessionId = buildUserPairSessionId(fromUserId, toUserId);

        // 1. 落库
        Message msg = Message.builder()
                .sessionId(normalizedSessionId)
                .fromUserId(fromUserId)
                .toUserId(toUserId)
                .content(content)
                .messageType(messageType != null ? messageType : "text")
                .status("sent")
                .build();
        msg = messageRepository.save(msg);

        // 2. 通过 WebSocket 推送给接收方
        var sender = userRepository.findById(fromUserId).orElse(null);
        String senderName = sender != null ? UserFormatter.formatPersonName(sender) : "用户";

        WebSocketMessage wsMsg = new WebSocketMessage();
        wsMsg.setType("chat_message");
        wsMsg.setSessionId(normalizedSessionId);
        wsMsg.setFromUserId(fromUserId.toString());
        wsMsg.setFromUserName(senderName);
        wsMsg.setToUserId(toUserId.toString());
        wsMsg.setContent(content);
        wsMsg.setMessageType(messageType != null ? messageType : "text");
        wsMsg.setId(msg.getId());
        wsMsg.setCreatedAt(msg.getCreatedAt().toString());

        webSocketHandler.sendToUser(toUserId.toString(), wsMsg);
        log.debug("Message {} saved and relayed: {} → {}", msg.getId(), fromUserId, toUserId);

        return msg;
    }

    /**
     * 游标分页拉取会话历史（倒序，最新在前）。
     * 优先按 sessionId 精确查询；若无结果则回退到用户对查询，
     * 兼容旧版 IDLE_* / HELP_* 格式的 sessionId 历史数据。
     */
    public List<Message> getHistory(String sessionId, Long beforeId, int size) {
        List<Message> messages;
        if (beforeId != null && beforeId > 0) {
            messages = messageRepository.findBeforeBySession(sessionId, beforeId, PageRequest.of(0, size));
        } else {
            messages = messageRepository.findLatestBySession(sessionId, PageRequest.of(0, size));
        }

        // 回退：旧版数据可能使用 IDLE_* / HELP_* 等非归一化 sessionId，按用户对查找
        if (messages.isEmpty() && sessionId != null && sessionId.startsWith("USER_")) {
            Long[] pair = parseUserPairSessionId(sessionId);
            if (pair != null) {
                if (beforeId != null && beforeId > 0) {
                    messages = messageRepository.findBeforeBetweenUsers(pair[0], pair[1], beforeId, PageRequest.of(0, size));
                } else {
                    messages = messageRepository.findLatestBetweenUsers(pair[0], pair[1], PageRequest.of(0, size));
                }
            }
        }
        return messages;
    }

    /**
     * 撤回消息：仅发送方可在 2 分钟内撤回。
     * 撤回后通过 WebSocket 通知接收方更新消息状态。
     */
    public Message recallMessage(Long messageId, Long userId) {
        Message msg = messageRepository.findById(messageId)
                .orElseThrow(() -> new BizException("消息不存在"));

        if (!msg.getFromUserId().equals(userId)) {
            throw new BizException("只能撤回自己发送的消息");
        }

        if (msg.getRecalledAt() != null) {
            throw new BizException("消息已被撤回");
        }

        long minutes = java.time.Duration.between(msg.getCreatedAt(), java.time.LocalDateTime.now()).toMinutes();
        if (minutes > 2) {
            throw new BizException("超过2分钟的消息无法撤回");
        }

        msg.setRecalledAt(java.time.LocalDateTime.now());
        msg = messageRepository.save(msg);

        // 通过 WebSocket 通知接收方
        WebSocketMessage wsMsg = new WebSocketMessage();
        wsMsg.setType("chat_recall");
        wsMsg.setSessionId(msg.getSessionId());
        wsMsg.setId(msg.getId());
        wsMsg.setFromUserId(userId.toString());
        wsMsg.setToUserId(msg.getToUserId().toString());
        webSocketHandler.sendToUser(msg.getToUserId().toString(), wsMsg);

        log.info("Message {} recalled by user {}", messageId, userId);
        return msg;
    }

    // ============================================================
    // 会话列表（消息页后端数据源 — 弥补纯本地存储的不可靠性）
    // ============================================================

    /**
     * 获取用户参与的全部会话摘要，按最新消息时间倒序。
     * 消息页优先使用本地存储（即时、离线可用），后端数据作为补充和修复源。
     */
    public List<ChatSessionDTO> getUserSessions(Long userId) {
        List<String> sessionIds = messageRepository.findDistinctSessionsByUser(userId);
        List<ChatSessionDTO> result = new ArrayList<>();
        for (String sid : sessionIds) {
            List<Message> latest = messageRepository.findLatestBySession(sid, PageRequest.of(0, 1));
            if (latest.isEmpty()) continue;
            Message lastMsg = latest.get(0);

            Long otherUserId = lastMsg.getFromUserId().equals(userId)
                    ? lastMsg.getToUserId() : lastMsg.getFromUserId();
            String otherUserName = userRepository.findById(otherUserId)
                    .map(UserFormatter::formatPersonName).orElse("用户");

            result.add(ChatSessionDTO.builder()
                    .sessionId(sid)
                    .otherUserId(otherUserId.toString())
                    .otherUserName(otherUserName)
                    .lastMessage(lastMsg.getContent())
                    .lastMessageType(lastMsg.getMessageType())
                    .lastTime(lastMsg.getCreatedAt().toString())
                    .build());
        }
        // 按 lastMsg.createdAt 倒序（latest first）
        result.sort((a, b) -> String.valueOf(b.getLastTime()).compareTo(String.valueOf(a.getLastTime())));
        return result;
    }

    // ============================================================
    // 会话 ID 工具方法
    // ============================================================

    /** 构建归一化会话 ID：USER_{较小用户ID}_{较大用户ID} */
    private String buildUserPairSessionId(Long uid1, Long uid2) {
        long smaller = Math.min(uid1, uid2);
        long larger = Math.max(uid1, uid2);
        return "USER_" + smaller + "_" + larger;
    }

    /** 解析 USER_{uid1}_{uid2} 格式的会话 ID，返回 [uid1, uid2]，解析失败返回 null */
    private Long[] parseUserPairSessionId(String sessionId) {
        try {
            String[] parts = sessionId.split("_");
            if (parts.length == 3 && "USER".equals(parts[0])) {
                return new Long[]{ Long.valueOf(parts[1]), Long.valueOf(parts[2]) };
            }
        } catch (NumberFormatException ignored) {
            log.debug("解析会话ID格式失败", ignored);
        }
        return null;
    }

}
