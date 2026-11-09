package com.example.copilot.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带 HTTP 状态码与业务码，是 Service 层主动中断流程的统一手段。
 *
 * <p>面试要点：把「状态 / 权限 / 冲突」等可预期的业务失败与 500 系统错误区分开——
 * Service 只需 {@code throw} 语义明确的异常，由 {@link GlobalExceptionHandler}
 * 集中翻译成 {@code ApiResponse} 加对应 HTTP 状态，Controller 无需到处 try-catch。
 *
 * <p>典型语义：400 参数或业务规则错误（无参构造默认值）、404 资源不存在、
 * 403 禁止（数据级权限校验失败）、409 冲突。
 * 409 专用于「请求本身合法但与资源当前状态冲突」，如工单 version 乐观锁版本落后、
 * 状态机非法流转、唯一键重复，与 400（请求错）、403（无权限）严格区分；
 * 状态码同时写入 {@link #code}，使响应体业务码与 HTTP status 一致。
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 映射的 HTTP 状态，决定最终响应状态码与前端通用错误分类。 */
    private final HttpStatus status;
    /** 业务码，取 {@code status.value()}（如 409 / 403），与 ApiResponse.code 对齐。 */
    private final int code;

    /**
     * 指定 HTTP 状态与消息构造异常。
     * <p>关键规则：code 直接取 status 的数值，全局异常处理器据此回填响应体；
     * 不可变字段（final）保证异常一旦抛出，状态语义不被中途改写。
     */
    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
        this.code = status.value();
    }

    /**
     * 简化构造，默认 400 BAD_REQUEST。
     * <p>供参数校验失败等无需区分具体状态的场景使用，等价于传入 HttpStatus.BAD_REQUEST。
     */
    public BusinessException(String message) {
        this(HttpStatus.BAD_REQUEST, message);
    }

    /** 资源不存在 → 404，常用于按 ID 查询不到会话 / 工单 / 文档。 */
    public static BusinessException notFound(String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, message);
    }

    /** 越权或数据级权限校验不通过 → 403；区别于 401（未认证），此处用户已登录但无权访问该资源。 */
    public static BusinessException forbidden(String message) {
        return new BusinessException(HttpStatus.FORBIDDEN, message);
    }

    /** 资源状态冲突 → 409，典型为乐观锁版本落后、状态机非法迁移、重复提交。 */
    public static BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, message);
    }

    /** 参数或业务规则不合法 → 400，是无参构造的显式语义别名。 */
    public static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
}
