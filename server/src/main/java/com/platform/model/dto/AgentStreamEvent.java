package com.platform.model.dto;

import com.platform.common.AgentEventType;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Agent 对话流式事件（SSE）。
 *
 * <p>序列化后形如 {@code {"type":"answer","data":"..."}}，C端按 {@code type} 分派处理。
 * 事件类型取值集中定义在 {@link AgentEventType}，本类不再维护一份文字说明（避免两处脱节）。</p>
 */
@Data
@AllArgsConstructor
public class AgentStreamEvent {

    /** 事件类型：start/answer/sources/action/replace/clear/end/error，见 {@link AgentEventType} */
    private AgentEventType type;

    /** 事件数据：answer/replace/error 为文本，sources/action 为数组，start/end/clear 为状态对象 */
    private Object data;
}
