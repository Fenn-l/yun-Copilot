package com.example.copilot.system.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求体，用于 POST {@code /api/auth/login}。
 *
 * <p>由 Controller 的 {@code @Valid} 触发 Bean Validation，仅接收用户名与明文密码两个字段；
 * 明文密码只在传输与 BCrypt 比对过程中短暂存在，绝不落库、绝不被回显。
 * 登录接口在 SecurityConfig 中对 {@code /api/auth/**} 放行，无需携带 Token。
 */
@Data
public class LoginRequest {

    /**
     * 登录用户名，必填；{@code @NotBlank} 拒绝 null、空串与全空白。
     * 正确性由 Spring Security 按用户名查库后交给 BCrypt 校验，不在此做格式限制。
     */
    @NotBlank(message = "请输入用户名")
    private String username;

    /**
     * 登录密码（明文传输），必填；{@code @NotBlank} 仅保证非空。
     * 登录阶段不重复长度校验，密码是否正确完全由 DaoAuthenticationProvider 的 BCrypt 比对决定，
     * 避免因校验规则泄露口令策略或误拒历史账号。
     */
    @NotBlank(message = "请输入密码")
    private String password;
}
