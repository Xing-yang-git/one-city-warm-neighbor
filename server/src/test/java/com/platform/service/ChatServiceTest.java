package com.platform.service;

import com.platform.common.AppTimeZone;
import com.platform.model.dto.WebSocketMessage;
import com.platform.model.entity.Message;
import com.platform.model.entity.User;
import com.platform.repository.MessageRepository;
import com.platform.repository.UserRepository;
import com.platform.websocket.ChatWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatService 单元测试")
class ChatServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private ChatWebSocketHandler webSocketHandler;

    @InjectMocks
    private ChatService chatService;

    private Long fromUserId;
    private Long toUserId;

    @BeforeEach
    void setUp() {
        fromUserId = 1L;
        toUserId = 2L;
        // save() 返回入参并补全 createdAt（模拟 JPA @PrePersist）
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            if (m.getCreatedAt() == null) {
                m.setCreatedAt(java.time.LocalDateTime.now(AppTimeZone.APP_ZONE));
            }
            return m;
        });
    }

    @Test
    @DisplayName("转发消息 - 发送方存在时使用其姓名")
    void should_relayMessage_when_senderExists() {
        // 准备
        User sender = User.builder()
                .id(fromUserId)
                .name("张三")
                .build();
        when(userRepository.findById(fromUserId)).thenReturn(Optional.of(sender));

        // 执行
        chatService.sendMessage(fromUserId, toUserId, "你好", "text", "session-abc");

        // 断言
        ArgumentCaptor<WebSocketMessage> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(webSocketHandler).sendToUser(eq(toUserId.toString()), captor.capture());
        WebSocketMessage msg = captor.getValue();
        assertThat(msg.getType()).isEqualTo("chat_message");
        assertThat(msg.getSessionId()).isEqualTo("USER_1_2");
        assertThat(msg.getFromUserId()).isEqualTo(fromUserId.toString());
        assertThat(msg.getFromUserName()).isEqualTo("张三(业主)");
        assertThat(msg.getToUserId()).isEqualTo(toUserId.toString());
        assertThat(msg.getContent()).isEqualTo("你好");
        assertThat(msg.getMessageType()).isEqualTo("text");
        assertThat(msg.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("转发消息 - 发送方不存在时使用默认姓名")
    void should_relayMessage_when_senderNotFound() {
        // 准备
        when(userRepository.findById(fromUserId)).thenReturn(Optional.empty());

        // 执行
        chatService.sendMessage(fromUserId, toUserId, "测试内容", "image", "session-xyz");

        // 断言
        ArgumentCaptor<WebSocketMessage> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(webSocketHandler).sendToUser(eq(toUserId.toString()), captor.capture());
        WebSocketMessage msg = captor.getValue();
        assertThat(msg.getFromUserName()).isEqualTo("用户");
        assertThat(msg.getMessageType()).isEqualTo("image");
        assertThat(msg.getContent()).isEqualTo("测试内容");
    }

    @Test
    @DisplayName("转发消息 - messageType为null时默认text")
    void should_relayMessage_when_messageTypeNull() {
        // 准备
        when(userRepository.findById(fromUserId)).thenReturn(Optional.empty());

        // 执行
        chatService.sendMessage(fromUserId, toUserId, "空类型消息", null, "session-1");

        // 断言
        ArgumentCaptor<WebSocketMessage> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(webSocketHandler).sendToUser(eq(toUserId.toString()), captor.capture());
        WebSocketMessage msg = captor.getValue();
        assertThat(msg.getMessageType()).isEqualTo("text");
    }
}
