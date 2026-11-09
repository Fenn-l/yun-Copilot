package com.example.copilot.system.controller;

import com.example.copilot.common.result.ApiResponse;
import com.example.copilot.system.dto.AuthResponse;
import com.example.copilot.system.dto.LoginRequest;
import com.example.copilot.system.dto.RegisterRequest;
import com.example.copilot.system.dto.UserVO;
import com.example.copilot.system.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证 REST 入口，路径前缀 {@code /api/auth}，对外暴露注册、登录、取当前登录人三个端点。
 *
 * <p>{@code @RestController} 等价于 {@code @Controller} 加 {@code @ResponseBody}，
 * 所有返回值统一包装为 ApiResponse（结构为 code / message / data），由全局响应约定序列化为 JSON。
 *
 * <p>鉴权特点：SecurityConfig 对 {@code /api/auth/**} 整体 permitAll，注册与登录无需 Token；
 * 本控制器不设 {@code @PreAuthorize} 路由级鉴权（认证接口本就对匿名开放），
 * 业务接口的权限控制由 UserController / AdminUserController 各自承担。
 * 需特别注意：{@code /api/auth/me} 虽路径放行，但业务上依赖 JwtAuthFilter 从 Bearer Token
 * 写入的登录上下文，前端必须在登录后携带有效 Token 调用。
 *
 * <p>{@code @RequiredArgsConstructor} 由 Lombok 为 final 的 AuthService 生成构造器注入。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    /**
     * 认证服务，承载注册、登录、取当前登录人的全部业务逻辑。
     */
    private final AuthService authService;

    /**
     * 注册新用户：POST {@code /api/auth/register}。
     * <p>{@code @Valid} 触发 RegisterRequest 的 {@code @NotBlank} / {@code @Size} / {@code @Pattern} 校验，
     * 失败由全局异常处理器映射为参数错误响应。校验通过后委托 AuthService.register 完成
     * 用户名唯一判重、BCrypt 加密、固定 USER 角色与 JWT 签发，成功结果经 ApiResponse.ok 包装。
     *
     * <p>无 {@code @PreAuthorize}，匿名可访问。边界与异常：参数不合法返回校验错误，
     * 用户名已存在由 Service 抛 409 冲突；响应中的用户信息为脱敏 UserVO，不含密码哈希。
     *
     * @param request 注册请求体
     * @return 统一响应，data 为 JWT 与脱敏用户视图（AuthResponse）
     */
    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    /**
     * 登录：POST {@code /api/auth/login}。
     * <p>{@code @Valid} 校验 LoginRequest 的用户名与密码非空；随后委托 AuthService.login，
     * 由 Spring Security 完成 BCrypt 密码比对与账号启用状态检查，成功后签发 JWT。
     * 返回经 ApiResponse.ok 包装的 token 与脱敏用户信息。
     *
     * <p>无 {@code @PreAuthorize}，匿名可访问。边界与异常：参数校验失败返回参数错误；
     * 密码错误或账号被禁用由认证链抛出并统一映射为错误响应；用户绝不会在响应中看到任何账号的密码哈希。
     *
     * @param request 登录请求体
     * @return 统一响应，data 为 JWT 与脱敏用户视图（AuthResponse）
     */
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    /**
     * 取当前登录人：GET {@code /api/auth/me}。
     *
     * <p>如何取当前登录人：方法不接收任何参数，也不信任前端传入的用户标识。
     * 登录人由 JwtAuthFilter 解析请求头 Authorization: Bearer Token 后写入 SecurityContext，
     * AuthService.me() 从上下文中的 UserPrincipal 取用户 ID 再回查数据库，最终转为脱敏 UserVO。
     * 这样既防伪造身份，又能返回角色 / 状态变更后的最新值。
     *
     * <p>边界与异常：路径虽在 permitAll 范围内，但未携带有效 Token 时上下文为匿名主体，
     * 无法取得当前登录人；因此前端须登录后携带 Token 调用。返回值绝不含密码哈希。
     *
     * @return 统一响应，data 为当前登录用户的脱敏视图
     */
    @GetMapping("/me")
    public ApiResponse<UserVO> me() {
        return ApiResponse.ok(authService.me());
    }
}
