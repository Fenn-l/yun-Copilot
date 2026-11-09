package com.example.copilot.common.security;

import com.example.copilot.system.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * 登录主体，实现 {@code UserDetails}，把 system_user 的字段映射为 Spring Security 所需的
 * 认证信息，并在请求线程内由 {@link CurrentUser} 读取以做数据级权限判断。
 *
 * <p>面试要点：三级角色 USER / AGENT / ADMIN 经 {@link #getAuthorities()} 映射为
 * {@code ROLE_XXX}（Spring Security 的 {@code hasRole} 约定前缀），供 {@code @PreAuthorize} 使用；
 * {@link #isAgent()} 刻意让 ADMIN 也返回 true，形成「ADMIN 包含 AGENT 包含 USER」的权限包含关系，
 * 使 AGENT 可跨用户处理工单、ADMIN 可管理全站。
 *
 * <p>安全设计：字段全部 final 且不可变，一次构造后随 SecurityContext 存活于单个请求；
 * password 虽保留在主体内，但 JwtAuthFilter 构造认证令牌时 credentials 传 null，
 * 且该对象不会被序列化进任何响应，降低密码驻留与外泄风险。
 * {@link #isEnabled()} 仅在 status 为 1 时返回 true，与过滤器的状态校验呼应。
 */
@Getter
public class UserPrincipal implements UserDetails {

    /** 用户 ID，数据级权限过滤（ownerId / userId）的主键来源。 */
    private final Long id;
    /** 登录用户名，UserDetails.getUsername 的返回值。 */
    private final String username;
    /** 密码哈希（BCrypt）；仅用于登录比对，不参与 JWT 请求鉴权、不外泄到响应。 */
    private final String password;
    /** 展示昵称，供前端显示，不参与任何鉴权判断。 */
    private final String nickname;
    /** 角色字符串：USER / AGENT / ADMIN 三选一，决定 ROLE_ 权限与可见数据范围。 */
    private final String role;
    /** 账户状态：1 启用、其它值禁用；isEnabled 与过滤器据此拒绝停用账户。 */
    private final Integer status;

    /**
     * 全字段构造，通常由 {@link #from(User)} 调用。
     * <p>不可变：所有字段 final，构造后不可修改，避免请求处理中途主体被篡改。
     */
    public UserPrincipal(Long id, String username, String password, String nickname, String role, Integer status) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.nickname = nickname;
        this.role = role;
        this.status = status;
    }

    /**
     * 由 User 实体转换为主体，只取认证所需字段，屏蔽实体其余属性。
     * <p>在登录（SecurityUserDetailsService）与 JWT 请求（JwtAuthFilter）两条路径复用，
     * 保证两种认证方式拿到同一套角色 / 状态语义，避免权限判断分叉。
     */
    public static UserPrincipal from(User user) {
        return new UserPrincipal(user.getId(), user.getUsername(), user.getPassword(),
                user.getNickname(), user.getRole(), user.getStatus());
    }

    /** 是否管理员（ADMIN）：用于全量数据访问与管理端操作放行。 */
    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    /**
     * 是否具备客服 / 管理权限（AGENT 或 ADMIN）。
     * <p>关键：ADMIN 经 {@code || isAdmin()} 被包含，体现权限层级继承；
     * {@link CurrentUser#canManageAll()} 直接复用本方法判断能否跨用户处理工单。
     */
    public boolean isAgent() {
        return "AGENT".equals(role) || isAdmin();
    }

    /**
     * 返回权限集合：单元素 {@code ROLE_ + role}。
     * <p>前缀 ROLE_ 是 {@code hasRole('ADMIN')} 的隐式约定，缺前缀会导致方法级鉴权失效；
     * 返回不可变 List，角色不在此动态变化，角色变更依赖重新登录 / 每请求查库重建主体。
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    /** 账户未过期：本系统未实现账户有效期，恒 true；可按需扩展为按时间停用。 */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /** 账户未锁定：未实现登录失败锁定策略，恒 true；可在此接入锁定计数。 */
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    /** 凭证未过期：未实现密码有效期 / 强制改密，恒 true。 */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * 账户是否可用：status 非 null 且等于 1。
     * <p>边界：先判 null 再拆箱比较，status 为 null 时返回 false 而非抛 NPE；
     * 与 JwtAuthFilter 的 status==1 校验呼应，被禁用用户即使持有有效 Token 也无法通过认证。
     */
    @Override
    public boolean isEnabled() {
        // 先判空再拆箱，避免 Integer 为 null 时自动拆箱抛 NPE
        return status != null && status == 1;
    }
}
