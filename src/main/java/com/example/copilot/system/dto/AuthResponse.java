package com.example.copilot.system.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 认证成功响应体，作为 POST {@code /api/auth/login} 与 POST {@code /api/auth/register} 的 data 载荷，
 * 结构为「JWT + 脱敏用户」，一次返回即可让前端完成登录态建立与用户信息渲染。
 *
 * <p>{@code @AllArgsConstructor} 由 Lombok 生成全参构造器，供 AuthService 以
 * {@code new AuthResponse(token, UserVO.from(user))} 组装；user 必定是 UserVO 而非 User 实体，
 * 从出参类型上保证密码哈希不会随认证响应泄漏。
 */
@Data
@AllArgsConstructor
public class AuthResponse {

    /**
     * 签发的 JWT，subject 为用户 ID，附带 username、role 声明并设置过期时间。
     * 前端保存后，后续请求以请求头 Authorization: Bearer 形式携带，由 JwtAuthFilter 校验并重建登录上下文。
     */
    private String token;

    /**
     * 当前登录用户的脱敏视图（UserVO），不含密码哈希；
     * 前端据此渲染昵称、角色与权限菜单，无须再调用 {@code /api/auth/me}。
     */
    private UserVO user;
}
