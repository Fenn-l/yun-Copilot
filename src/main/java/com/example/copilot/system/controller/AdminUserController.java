package com.example.copilot.system.controller;

import com.example.copilot.common.result.ApiResponse;
import com.example.copilot.common.result.PageResult;
import com.example.copilot.system.dto.UserVO;
import com.example.copilot.system.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员专属用户管理入口，路径前缀 {@code /api/admin/users}，提供用户分页、改角色、启停账号三类能力。
 *
 * <p>路由级鉴权：类级 {@code @PreAuthorize("hasRole('ADMIN')")} 对类内所有端点统一生效，
 * 非 ADMIN 一律拒绝（403），未登录请求先被 SecurityConfig 的 authenticated 规则拦为 401。
 * 之所以放在类级而非逐方法标注，是为了保证新增管理端点默认继承管理员限制、不遗漏。
 *
 * <p>与 Service 层数据级鉴权的分工：{@code @PreAuthorize} 只回答「谁能访问这个 URL」，
 * 而「不能改 / 禁用自己」「角色白名单」「状态取值合法性」等与具体数据相关的规则，
 * 由 UserService 结合 CurrentUser.id() 在方法内部校验。两层叠加构成完整防护。
 *
 * <p>所有出参统一包装为 ApiResponse，用户数据经 UserVO（或 PageResult 包装的 UserVO）脱敏；
 * 请求体以 {@code @Valid} 触发 Bean Validation。
 * {@code @RequiredArgsConstructor} 由 Lombok 为 final 的 UserService 生成构造器注入。
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    /**
     * 用户管理服务，承担分页、改角色、启停及相应数据级校验。
     */
    private final UserService userService;

    /**
     * 用户分页列表：GET {@code /api/admin/users}。
     * <p>参数：page 默认 1、size 默认 10，keyword（用户名 / 昵称模糊）与 role（角色精确过滤）均为可选，
     * 缺省值由 {@code @RequestParam} 的 defaultValue 兜底，可选参数用 required = false 声明。
     * 委托 UserService.page 按创建时间倒序检索并逐条脱敏，返回 PageResult 包装的 UserVO。
     *
     * <p>权限：继承类级 ADMIN 限制。边界：无匹配时返回空列表且 total 为 0。
     *
     * @param page    页码，默认 1
     * @param size    每页条数，默认 10
     * @param keyword 模糊关键字，可为空
     * @param role    角色过滤，可为空
     * @return 统一响应，data 为脱敏用户分页结果
     */
    @GetMapping
    public ApiResponse<PageResult<UserVO>> page(@RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "10") long size,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String role) {
        return ApiResponse.ok(userService.page(page, size, keyword, role));
    }

    /**
     * 修改用户角色：PUT 改角色端点（路径前缀 {@code /api/admin/users}，路径变量 id 加 /role 后缀）。
     * <p>{@code @Valid} 触发 UpdateRoleRequest 的 {@code @NotBlank} 校验，保证 role 非空；
     * {@code @PathVariable} 绑定目标用户 ID，{@code @RequestBody} 绑定目标角色。
     * 委托 UserService.updateRole，由 Service 层再做 ROLES 白名单与「不能修改自己角色」的数据级校验。
     *
     * <p>权限：继承类级 ADMIN 限制。异常：role 为空返回参数错误；非法角色、修改自己均返回 400；
     * 目标用户不存在返回 404；成功返回脱敏 UserVO。
     *
     * @param id      目标用户 ID
     * @param request 含目标角色的请求体
     * @return 统一响应，data 为更新后的脱敏用户视图
     */
    @PutMapping("/{id}/role")
    public ApiResponse<UserVO> updateRole(@PathVariable Long id, @Valid @RequestBody UpdateRoleRequest request) {
        return ApiResponse.ok(userService.updateRole(id, request.getRole()));
    }

    /**
     * 启用 / 禁用账号：PUT 启停端点（路径前缀 {@code /api/admin/users}，路径变量 id 加 /status 后缀）。
     * <p>{@code @Valid} 触发 UpdateStatusRequest 的 {@code @NotNull} 校验，保证 status 非空；
     * 委托 UserService.updateStatus，由 Service 层校验取值仅 0（禁用）/ 1（启用），
     * 并执行「不能禁用当前登录账号」的自我操作保护。禁用后该账号已签发的 Token 即时失效。
     *
     * <p>权限：继承类级 ADMIN 限制。异常：status 为空返回参数错误；非法状态、禁用自己均返回 400；
     * 目标用户不存在返回 404；成功返回脱敏 UserVO。
     *
     * @param id      目标用户 ID
     * @param request 含目标状态的请求体
     * @return 统一响应，data 为更新后的脱敏用户视图
     */
    @PutMapping("/{id}/status")
    public ApiResponse<UserVO> updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateStatusRequest request) {
        return ApiResponse.ok(userService.updateStatus(id, request.getStatus()));
    }

    /**
     * 改角色请求体，仅用于上述 PUT 改角色端点的 {@code @RequestBody}。
     * <p>{@code @Data} 由 Lombok 生成 getter / setter，供 Jackson 反序列化及 Controller 取值；
     * 该内部类为承载入参的专用 DTO，不对外返回，因此不含任何敏感字段。
     * 校验职责分离：注解只保证非空，合法取值由 UserService 的 ROLES 白名单二次校验。
     */
    @Data
    public static class UpdateRoleRequest {
        /**
         * 目标角色，{@code @NotBlank} 保证非 null、非空串、非全空白。
         * 合法取值限定 USER / AGENT / ADMIN，白名单校验在 Service 层完成，防止越权设置非法角色。
         */
        @NotBlank(message = "角色不能为空")
        private String role;
    }

    /**
     * 启停账号请求体，仅用于上述 PUT 启停端点的 {@code @RequestBody}。
     * <p>{@code @Data} 由 Lombok 生成 getter / setter；注解只保证非空，
     * 取值合法性（仅 0 / 1）与自我操作保护由 UserService 承担。
     */
    @Data
    public static class UpdateStatusRequest {
        /**
         * 目标状态，{@code @NotNull} 保证非 null。
         * <p>取值 1 启用、0 禁用；因 0 是合法业务值，此处不用 {@code @NotBlank} 以免误拒 Integer 0。
         * 越界值由 Service 层拦截为 400。
         */
        @NotNull(message = "状态不能为空")
        private Integer status;
    }
}
