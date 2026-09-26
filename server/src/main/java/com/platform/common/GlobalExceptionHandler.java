package com.platform.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器 — 把各类异常映射为语义正确的 HTTP 状态码与统一 {@link Result} 响应体。
 *
 * <p>状态码约定：业务失败 400、未找到资源 404、方法不支持 405、并发冲突 409、
 * 上游（AI / 第三方）故障 502、未预期异常 500。</p>
 *
 * <p>注意：Spring MVC 抛出的标准客户端异常（如路径不存在、方法不支持）必须单独处理，
 * 否则会落入 {@link #handleAll} 被统一映射为 500。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getDefaultMessage())
                .findFirst()
                .orElse("请求参数校验失败");
        return ResponseEntity.badRequest().body(Result.error(400, msg));
    }

    @ExceptionHandler(AiGenerationException.class)
    public ResponseEntity<Result<Void>> handleAiGeneration(AiGenerationException e) {
        log.error("AI 生成失败: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Result.error(502, "AI 生成失败：" + e.getMessage()));
    }

    /**
     * Spring AI 客户端调用失败（deepseek/智谱 API 网络错误、限流、5xx）→ 502。
     *
     * <p>迁移 Spring AI 后，上游 AI 调用失败抛 {@code NonTransientAiException}/{@code TransientAiException}，
     * 若不在此处理会落入 {@link #handleRuntime} 映射为 400（错误语义不符：上游故障应为 502）。</p>
     */
    @ExceptionHandler({
            org.springframework.ai.retry.NonTransientAiException.class,
            org.springframework.ai.retry.TransientAiException.class
    })
    public ResponseEntity<Result<Void>> handleSpringAiClient(Exception e) {
        log.error("AI 服务调用失败: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Result.error(502, "AI 服务暂时不可用，请稍后重试"));
    }

    /**
     * 外部第三方服务（非 AI）调用失败 → 502。
     *
     * <p>与 {@link #handleAiGeneration} 同为上游故障，分设处理器以便日志按依赖方定位故障源。</p>
     */
    @ExceptionHandler(UpstreamServiceException.class)
    public ResponseEntity<Result<Void>> handleUpstreamService(UpstreamServiceException e) {
        log.error("上游服务调用失败: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Result.error(502, e.getMessage()));
    }

    @ExceptionHandler(VersionConflictException.class)
    public ResponseEntity<Result<Void>> handleVersionConflict(VersionConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Result.error(409, e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result<Void>> handleRuntime(RuntimeException e) {
        return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
    }

    /**
     * Spring MVC 标准客户端异常（404 / 405 / 406 / 415 / 缺参 400）→ 保留原始状态码。
     *
     * <p>这些异常若不单独处理，会落入 {@link #handleAll} 被统一映射为 500，后果有二：
     * 一是「路径写错」被误报为服务端故障，污染监控告警；
     * 二是前端无法区分「接口不存在」与「服务不可用」，只能统一提示"服务器异常"。</p>
     */
    @ExceptionHandler({
            NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class,
            HttpMediaTypeNotSupportedException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<Result<Void>> handleSpringMvcClientError(Exception e) {
        HttpStatusCode status = e instanceof ErrorResponse errorResponse
                ? errorResponse.getStatusCode()
                : HttpStatus.BAD_REQUEST;
        // 客户端错误属预期内流量（含爬虫探测），记 WARN 且不打堆栈，避免日志噪音
        log.warn("请求不合法 [{}] {}: {}", status.value(), e.getClass().getSimpleName(), e.getMessage());
        return ResponseEntity.status(status)
                .headers(resolveProtocolHeaders(e))
                .body(Result.error(status.value(), mvcClientErrorMessage(status)));
    }

    /**
     * 回填 Spring 默认解析器本会给出的协议头。
     *
     * <p>本处理器接管这些异常后 {@code DefaultHandlerExceptionResolver} 不再兜底，
     * 405 的 {@code Allow} 与 415 的 {@code Accept} 会一并丢失。RFC 7231 要求 405 必须
     * 携带 {@code Allow} 声明该资源支持的请求方法，故在此显式回填，避免因接管异常而削弱协议语义。</p>
     *
     * @param e 已捕获的 Spring MVC 客户端异常
     * @return 需附加的响应头；异常类型无对应信息时返回空头对象
     */
    private HttpHeaders resolveProtocolHeaders(Exception e) {
        HttpHeaders headers = new HttpHeaders();
        if (e instanceof HttpRequestMethodNotSupportedException methodNotSupported
                && methodNotSupported.getSupportedHttpMethods() != null) {
            headers.setAllow(methodNotSupported.getSupportedHttpMethods());
        } else if (e instanceof HttpMediaTypeNotSupportedException mediaTypeNotSupported
                && !mediaTypeNotSupported.getSupportedMediaTypes().isEmpty()) {
            // 空集合会写出一个值为空的 Accept 头，故仅在非空时回填
            headers.setAccept(mediaTypeNotSupported.getSupportedMediaTypes());
        }
        return headers;
    }

    /**
     * 将 Spring MVC 客户端异常的状态码映射为面向调用方的中文提示。
     *
     * @param status HTTP 状态码
     * @return 用户可读的提示文案
     */
    private String mvcClientErrorMessage(HttpStatusCode status) {
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return "请求的资源不存在";
        }
        if (status.isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED)) {
            return "不支持的请求方法";
        }
        if (status.isSameCodeAs(HttpStatus.UNSUPPORTED_MEDIA_TYPE)) {
            return "不支持的请求内容类型";
        }
        if (status.isSameCodeAs(HttpStatus.NOT_ACCEPTABLE)) {
            return "无法提供客户端要求的响应格式";
        }
        return "请求参数缺失或格式不正确";
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleAll(Exception e) {
        log.error("未预期异常: {} - {}", e.getClass().getName(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error("服务器内部错误，请稍后重试"));
    }
}
