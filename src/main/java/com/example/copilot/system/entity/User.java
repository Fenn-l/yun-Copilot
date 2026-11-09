package com.example.copilot.system.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户实体，对应数据库表 cs_user，是整个平台认证与权限体系的唯一用户数据源。
 *
 * <p>在业务链路中的位置：注册时由 AuthService 落库；登录时由 SecurityUserDetailsService 按用户名查询，
 * 交给 DaoAuthenticationProvider 做 BCrypt 密码比对；此后每个携带 JWT 的请求都会由 JwtAuthFilter
 * 按 Token 中的用户 ID 重新查询本表，因此角色或状态的变更即时生效，旧 Token 无法继续越权。
 *
 * <p>关键设计与约束：
 * <ul>
 *   <li>role 为三级角色字符串：USER（普通用户）、AGENT（客服）、ADMIN（管理员）；注册固定赋予 USER。</li>
 *   <li>status 为账号状态：1 启用、0 禁用；禁用账号无法通过登录，也不会被 JwtAuthFilter 建立会话。</li>
 *   <li>password 存 BCrypt 哈希且被 {@code @JsonIgnore} 排除序列化，绝不出现在对外响应中；
 *       对外统一使用 UserVO 脱敏视图。</li>
 *   <li>createTime / updateTime 由 MyBatis-Plus 自动填充，业务代码不手动赋值。</li>
 * </ul>
 */
@Data
@TableName("cs_user")
public class User {

    /**
     * 主键 ID，自增（IdType.AUTO）；JWT 的 subject 即为此值，用于按 ID 回查当前用户。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 登录用户名，全表唯一；注册时先 selectCount 判重，登录时作为认证账号。
     */
    private String username;

    /**
     * BCrypt 加密后的密码哈希，永不明文存储、永不返回前端。
     * <p>{@code @JsonIgnore} 保证即使本实体被直接序列化也不会泄漏哈希；
     * 对外只暴露 UserVO，从类型层面杜绝密码哈希外泄。
     */
    @JsonIgnore
    private String password;

    /**
     * 显示昵称；注册未填写时由 AuthService 兜底为用户名。
     */
    private String nickname;

    /**
     * 邮箱，仅作资料展示与联系信息，当前不做唯一性校验与格式强校验。
     */
    private String email;

    /**
     * 角色，取值 USER / AGENT / ADMIN 三级：
     * <ul>
     *   <li>USER：普通用户，仅能发起会话、提交与查看自己的工单；</li>
     *   <li>AGENT：客服，可接单、处理工单、使用 Copilot 辅助；</li>
     *   <li>ADMIN：管理员，独占 {@code /api/admin/users} 下的用户分页、改角色、启停账号等管理能力。</li>
     * </ul>
     * <p>安全约束：注册接口不接受前端传入角色，固定赋予 USER 以防越权提权；
     * 仅 ADMIN 可经 UserService.updateRole 白名单校验后修改，且不允许修改自己的角色。
     */
    private String role;

    /**
     * 账号状态：1 启用、0 禁用。
     * <p>UserPrincipal.isEnabled() 据此判定：禁用账号即使密码正确也会被 Spring Security 拒绝登录，
     * JwtAuthFilter 同样不会为其建立 SecurityContext；仅 ADMIN 可启停，且不能禁用自己。
     */
    private Integer status;

    /**
     * 创建时间，INSERT 时由 MyBatis-Plus 自动填充（FieldFill.INSERT），落库后不再变化。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间，INSERT 与 UPDATE 时均由 MyBatis-Plus 自动填充（FieldFill.INSERT_UPDATE），
     * 记录资料、角色或状态最后一次变更的时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
