package com.example.copilot.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求体，用于 POST {@code /api/auth/register}。
 *
 * <p>Controller 以 {@code @Valid} 触发字段校验；本 DTO 刻意只接收资料字段，
 * 不含 role 与 status —— 注册一律固定赋予 USER 角色与启用状态，前端无法借注册接口自我提权，
 * 这是防越权的第一道约束。注册成功后 AuthService 直接签发 JWT，实现「注册即登录」。
 */
@Data
public class RegisterRequest {

    /**
     * 登录用户名，必填且全表唯一。
     * <p>校验约束：{@code @NotBlank} 保证非空；{@code @Size(min = 3, max = 32)} 限制长度；
     * {@code @Pattern} 限定仅字母、数字、下划线，避免特殊字符带来的匹配与注入问题。
     * 格式校验之外，AuthService 还会做一次 selectCount 唯一性判重，重复时抛 409 冲突。
     */
    @NotBlank(message = "请输入用户名")
    @Size(min = 3, max = 32, message = "用户名长度需在 3-32 位之间")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
    private String username;

    /**
     * 登录密码（明文传输），必填。
     * <p>校验约束：{@code @NotBlank} 保证非空，{@code @Size(min = 6, max = 64)} 限定 6-64 位；
     * 落库前由 AuthService 用 BCrypt 加密，数据库及任何出参都不保留明文。
     */
    @NotBlank(message = "请输入密码")
    @Size(min = 6, max = 64, message = "密码长度需在 6-64 位之间")
    private String password;

    /**
     * 昵称，选填；{@code @Size(max = 32)} 限制最长 32 字符。
     * 为空时由 AuthService 兜底为用户名，保证展示字段始终有值。
     */
    @Size(max = 32, message = "昵称过长")
    private String nickname;

    /**
     * 邮箱，选填；当前无校验注解，仅作资料展示与联系信息，不参与登录与唯一性约束。
     */
    private String email;
}
