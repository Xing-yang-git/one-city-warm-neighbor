package com.platform.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleAll(Exception e) {
        log.error("未预期异常: {} - {}", e.getClass().getName(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error("服务器内部错误，请稍后重试"));
    }
}
