package com.example.copilot.system.dto;

import com.example.copilot.system.entity.User;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户对外视图（脱敏 VO），用于登录 / 注册响应（AuthResponse.user）、GET {@code /api/auth/me}、
 * 管理员用户分页 {@code /api/admin/users} 以及客服列表 {@code /api/users/agents} 等所有返回用户信息的接口。
 *
 * <p>为何绝不包含密码哈希：本 VO 是 User 实体的「安全子集」，从结构上就没有 password 字段，
 * 由 from() 按白名单逐字段拷贝生成，任何序列化路径都不可能带出 BCrypt 哈希。
 * 相比仅在实体上用 {@code @JsonIgnore}，VO 从类型层面彻底切断敏感字段外泄，是接口出参的最小权限实践。
 *
 * <p>字段均为展示与鉴权所需的非敏感信息；{@code @Builder} 配合静态方法 from 完成组装。
 */
@Data
@Builder
public class UserVO {

    /**
     * 用户 ID，脱敏视图主键；前端据此关联会话、工单等资源。
     */
    private Long id;
    /**
     * 登录用户名，展示用；不含任何凭据信息。
     */
    private String username;
    /**
     * 显示昵称，用于界面展示。
     */
    private String nickname;
    /**
     * 邮箱，仅作资料展示，属非敏感出参。
     */
    private String email;
    /**
     * 角色，取值 USER / AGENT / ADMIN；前端据此渲染菜单与可操作范围。
     */
    private String role;
    /**
     * 账号状态：1 启用、0 禁用；禁用账号不会出现在客服分配列表中。
     */
    private Integer status;
    /**
     * 注册（创建）时间，用于用户列表展示与排序依据。
     */
    private LocalDateTime createTime;

    /**
     * 由 User 实体生成脱敏 UserVO，是实体到出参的唯一转换入口。
     * <p>关键规则：入参为 null 时直接返回 null（兼容 {@code /api/auth/me} 等查不到用户的兜底场景）；
     * 采用字段白名单拷贝，显式排除 password 等敏感字段，杜绝密码哈希泄漏。
     *
     * @param user 数据库用户实体，可为 null
     * @return 脱敏后的用户视图；user 为 null 时返回 null
     */
    public static UserVO from(User user) {
        // null 安全：查不到用户时返回 null，由上层 ApiResponse 包装，避免 NPE 与敏感字段拼装
        if (user == null) {
            return null;
        }
        // 仅拷贝对外安全字段，绝不涉及 password，保证 VO 结构上不含密码哈希
        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .role(user.getRole())
                .status(user.getStatus())
                .createTime(user.getCreateTime())
                .build();
    }
}
