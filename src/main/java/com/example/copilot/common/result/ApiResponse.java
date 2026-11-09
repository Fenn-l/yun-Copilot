package com.example.copilot.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一 API 返回结构。
 * code=0 表示成功，业务错误使用非 0 code 并由全局异常处理器映射 HTTP 状态。
 *
 * <p>面试要点：全站（含 SecurityConfig 里认证失败手工写出的 JSON）统一
 * {@code code / message / data} 三段式，前端只需判 code 定成败、取 data 拿载荷；
 * 泛型 {@code <T>} 承载任意数据类型，避免 Object 强转。
 * <p>成功码固定 0，失败码沿用 HTTP 数值（400 / 401 / 403 / 409 / 413 / 500），
 * 使「业务码」与「HTTP 状态」双轨一致，便于网关与前端统一拦截；
 * 失败时 data 恒为 null，前端无需为成功 / 失败兼容两套结构。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    /** 业务状态码：0 成功；非 0 失败，通常取对应 HTTP 数值，与响应 HTTP status 对齐。 */
    private int code;
    /** 面向用户的提示文案：成功固定 "ok"，失败为可展示的错误原因。 */
    private String message;
    /** 响应载荷：成功时为业务数据，失败或空响应为 null。 */
    private T data;

    /**
     * 构造带数据的成功响应（code=0、message="ok"）。
     * <p>纯工厂方法，无副作用、不查库；data 允许为 null，不做参数裁剪。
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    /**
     * 构造无数据的成功响应，常用于新增 / 删除 / 状态变更等无返回体的接口。
     * <p>与带参 ok 的区别仅在 data 固定为 null，返回类型收窄为 {@code ApiResponse<Void>}。
     */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(0, "ok", null);
    }

    /**
     * 构造失败响应，由调用方给定业务码与文案，data 恒为 null。
     * <p>全局异常处理器据此组装响应体；code 应与 HTTP status 语义一致（如 403 / 409 / 413），
     * 保证 ApiResponse.code 与 ResponseEntity 状态不打架。
     */
    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
