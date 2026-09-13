package com.platform.common;

/**
 * 上游服务异常 — 调用外部第三方服务（非 AI）失败时抛出，映射 HTTP 502。
 *
 * <p>与 {@link AiGenerationException} 的区别在于服务性质而非处理方式：两者都表示
 * 「我方无过错、上游不可用」，均映射 502，分设两个类是为了让日志与告警能按依赖方定位故障源。</p>
 *
 * <p>典型场景：微信 {@code code2Session} 接口网络超时或返回非 200。
 * 注意与「上游明确拒绝」区分——微信返回 {@code errcode}/{@code errmsg} 属于客户端凭据无效，
 * 应抛 {@link BizException}（400），而非本异常。</p>
 */
public class UpstreamServiceException extends RuntimeException {

    /**
     * 使用描述消息创建上游服务异常。
     *
     * @param message 异常描述信息
     */
    public UpstreamServiceException(String message) {
        super(message);
    }

    /**
     * 使用描述消息和原始异常创建上游服务异常。
     *
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public UpstreamServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
