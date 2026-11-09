package com.example.copilot.system.controller;

import com.example.copilot.common.result.ApiResponse;
import com.example.copilot.system.dto.UserVO;
import com.example.copilot.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 面向已登录用户的通用查询入口，路径前缀 {@code /api/users}，当前仅提供客服列表端点。
 *
 * <p>与 {@code /api/admin/users} 的分工：后者是管理员专属的用户管理（改角色、启停、分页），
 * 在类级统一加 {@code @PreAuthorize("hasRole('ADMIN')")}；本控制器只做只读查询，
 * 采用方法级 {@code @PreAuthorize} 按角色精细放行，属于「路由级鉴权」的第一层。
 *
 * <p>所有出参统一包装为 ApiResponse，用户数据一律经 UserVO 脱敏；
 * {@code @RequiredArgsConstructor} 由 Lombok 为 final 的 UserService 生成构造器注入。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    /**
     * 用户查询服务，负责按条件检索并脱敏用户数据。
     */
    private final UserService userService;

    /**
     * 客服列表：GET {@code /api/users/agents}，用于工单分配时选择处理人。
     *
     * <p>路由级鉴权：方法上 {@code @PreAuthorize("hasAnyRole('AGENT','ADMIN')")} 限定仅客服与管理员可查，
     * 普通 USER 访问将被拒绝（403），避免全量客服名单外泄；未登录请求则先被
     * SecurityConfig 的 anyRequest().authenticated() 拦截为 401。
     *
     * <p>委托 UserService.listAgents 取 role 为 AGENT / ADMIN 且 status 为 1 的启用账号，
     * 按 id 升序返回脱敏 UserVO 列表，绝不含密码哈希。
     * 边界：无符合条件的客服时返回空列表而非异常。
     *
     * @return 统一响应，data 为可用客服 / 管理员的脱敏列表
     */
    @GetMapping("/agents")
    @PreAuthorize("hasAnyRole('AGENT','ADMIN')")
    public ApiResponse<List<UserVO>> agents() {
        return ApiResponse.ok(userService.listAgents());
    }
}
