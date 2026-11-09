package com.example.copilot.common.exception;

import com.example.copilot.common.result.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理器（{@code @RestControllerAdvice}），把散落在各层的异常统一翻译为
 * {@code ResponseEntity<ApiResponse<Void>>}，保证前后端错误契约一致。
 *
 * <p>面试要点：Spring 按「最具体匹配」选择 {@code @ExceptionHandler}，因此业务 / 参数 / 安全异常
 * 在前，兜底 {@code Exception} 必须放最后，否则会抢先吞掉所有具体异常。
 * 处理语义分层：{@link BusinessException} 保留自带 HTTP 状态；参数校验与缺参 400；
 * 上传超限 413；{@code AccessDeniedException} 403、{@code AuthenticationException} 401；
 * 未知异常记完整堆栈后统一 500，绝不把异常信息回传前端。
 *
 * <p>与 Spring Security 的分工：过滤器链抛出的认证 / 授权异常尚未进入 DispatcherServlet，
 * 进不了本处理器，由 SecurityConfig 的 authenticationEntryPoint / accessDeniedHandler
 * 直接写 JSON；此处 handleAuth / handleDenied 兜的是已进入 MVC 之后的同类异常。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：用异常自带的 status 与 code 原样映射，保留 409 / 403 等精确语义。
     * <p>不改写消息、不降级为 500，是「可预期业务失败」与「系统故障」的分界。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.fail(ex.getCode(), ex.getMessage()));
    }

    /**
     * 参数校验失败（{@code @Valid} 的 MethodArgumentNotValidException 与 BindException 合并处理）→ 400。
     * <p>根因提取：只从 BindingResult 取第一条 FieldError，拼成「字段名: 默认消息」，
     * 对外暴露最小可读信息，避免一次性回传整包校验细节；无任何字段错误时用兜底文案。
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidation(BindException ex) {
        // 仅取第一条字段错误：对外暴露最小可读信息，避免一次性回传大量校验细节
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                // 拼 "字段名: 默认消息"，字段名与消息均来自校验注解，非用户输入直拼
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                // orElse 兜底：FieldError 为空时仍返回确定文案
                .orElse("请求参数不合法");
        return ResponseEntity.badRequest().body(ApiResponse.fail(400, message));
    }

    /** 缺少必填请求参数 → 400，回带具体缺失的参数名，便于前端快速定位。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(400, "缺少参数: " + ex.getParameterName()));
    }

    /** 上传文件超过大小限制 → 413 PAYLOAD_TOO_LARGE，与 AppProperties.maxUploadMb 及 multipart 配置呼应。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.fail(413, "文件超过大小限制"));
    }

    /** 已进入 MVC 后的授权拒绝 → 403；统一模糊文案，不暴露被访问资源的存在性或细节。 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail(403, "没有权限执行该操作"));
    }

    /** 认证失败（如登录密码错误）→ 401；固定模糊文案「用户名或密码错误」，防止用户名枚举攻击。 */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuth(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail(401, "用户名或密码错误"));
    }

    /**
     * 兜底异常处理：记录完整堆栈便于排查，对外只返回通用 500 文案。
     * <p>关键：日志把异常对象整体传入以保留栈轨迹，但响应体绝不含 ex 信息，
     * 杜绝堆栈、SQL、内部路径等敏感信息外泄；这是「未知异常」与可预期业务异常的根本分界。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception ex) {
        // 保留完整堆栈用于排障，但绝不把异常内容外泄到响应体
        log.error("未处理异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(500, "系统开小差了，请稍后重试"));
    }
}
