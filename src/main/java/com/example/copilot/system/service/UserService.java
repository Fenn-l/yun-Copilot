package com.example.copilot.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.copilot.common.exception.BusinessException;
import com.example.copilot.common.result.PageResult;
import com.example.copilot.common.security.CurrentUser;
import com.example.copilot.system.dto.UserVO;
import com.example.copilot.system.entity.User;
import com.example.copilot.system.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

/**
 * 用户查询与管理服务，支撑管理员用户管理（{@code /api/admin/users}）、
 * 工单分配用的客服列表（{@code /api/users/agents}）以及运营看板的用户总数统计。
 *
 * <p>路由级鉴权与数据级鉴权的分工（本包核心）：
 * <ul>
 *   <li>路由级：由 Controller 的 {@code @PreAuthorize} 完成——AdminUserController 类级
 *       {@code hasRole('ADMIN')} 挡住非管理员，UserController 方法级
 *       {@code hasAnyRole('AGENT','ADMIN')} 控制客服列表可见范围；</li>
 *   <li>数据级：本 Service 层承担与具体数据相关的校验——角色白名单 ROLES、状态取值合法性，
 *       以及「不能修改 / 禁用自己」的自我操作保护（CurrentUser.id() 取自安全上下文）。</li>
 * </ul>
 * 两层配合，既防越权访问，又防管理员误操作把自己降级或锁死导致系统失去唯一管理员。
 *
 * <p>所有对外返回统一经 UserVO.from 脱敏，绝不外泄实体中的密码哈希；
 * {@code @RequiredArgsConstructor} 由 Lombok 为 final 的 UserMapper 生成构造器注入。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    /**
     * 合法角色白名单：USER / AGENT / ADMIN。
     * <p>updateRole 的入参必须命中此集合，防止把 role 改成任意字符串而绕过既有权限体系。
     */
    private static final Set<String> ROLES = Set.of("USER", "AGENT", "ADMIN");

    /**
     * 用户表 Mapper，承担全部查询、分页与更新操作。
     */
    private final UserMapper userMapper;

    /**
     * 按 ID 强制查询用户，查不到即抛异常。
     * <p>被 updateRole / updateStatus 复用，集中处理「目标不存在」边界，避免调用方对 null 继续 setter 触发 NPE；
     * 返回的是完整 User 实体（含密码哈希），仅限服务内部使用，绝不作为接口出参。
     *
     * @param id 目标用户 ID
     * @return 用户实体
     * @throws BusinessException 用户不存在时抛 404（notFound）
     */
    public User requireById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            // 目标不存在：集中抛 404，阻断后续对 null 实体的写入
            throw BusinessException.notFound("用户不存在");
        }
        return user;
    }

    /**
     * 按用户名精确查询单个用户，供内部按账号定位（登录链路实际由 SecurityUserDetailsService 走同一条件）。
     * <p>selectOne 依赖 username 唯一，无匹配时返回 null 而不抛异常；纯查询，无事务。
     *
     * @param username 登录用户名
     * @return 用户实体；不存在时返回 null
     */
    public User findByUsername(String username) {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
    }

    /**
     * 管理员用户分页查询，支撑 GET {@code /api/admin/users}，按创建时间倒序展示最新注册用户。
     * <p>条件拼装：keyword 有值时同时模糊匹配 username 或 nickname，并用 and 包裹该 or 组合，
     * 避免 or 条件泄漏到外层影响角色过滤；role 有值时精确 eq 过滤；空串 / 空白由 hasText 跳过。
     * selectPage 为物理分页，PageResult.from(result, UserVO::from) 在映射分页元数据的同时逐条脱敏。
     *
     * <p>权限：由 AdminUserController 类级 {@code @PreAuthorize("hasRole('ADMIN')")} 保证，本方法不重复做路由级校验。
     * 边界：无匹配时返回空列表且 total 为 0；page / size 由 Controller 的默认值兜底。
     *
     * @param page    页码，从 1 起
     * @param size    每页条数
     * @param keyword 用户名 / 昵称模糊关键字，可为空
     * @param role    角色精确过滤，可为空
     * @return 脱敏后的用户分页结果
     */
    public PageResult<UserVO> page(long page, long size, String keyword, String role) {
        LambdaQueryWrapper<User> wrapper = Wrappers.<User>lambdaQuery()
                .orderByDesc(User::getCreateTime);
        if (StringUtils.hasText(keyword)) {
            // 用 and 包裹 username/nickname 的 or 模糊匹配，防止 or 泄漏到外层弱化其他过滤条件
            wrapper.and(w -> w.like(User::getUsername, keyword).or().like(User::getNickname, keyword));
        }
        if (StringUtils.hasText(role)) {
            // 角色有值时精确过滤；空串 / 空白不追加条件
            wrapper.eq(User::getRole, role);
        }
        Page<User> result = userMapper.selectPage(new Page<>(page, size), wrapper);
        // 转换分页结果的同时以方法引用逐条应用 UserVO.from 脱敏
        return PageResult.from(result, UserVO::from);
    }

    /**
     * 客服列表，支撑 GET {@code /api/users/agents}，用于工单分配时选择处理人。
     * <p>筛选条件：role 属于 AGENT 或 ADMIN 且 status 为 1（启用），按 id 升序保证顺序稳定。
     * 含 ADMIN 是因为管理员同样具备处理工单的能力；排除 USER 与禁用账号，保证分配目标有效、可登录。
     * 结果经 UserVO.from 逐条脱敏，绝不含密码哈希。
     *
     * <p>权限：由 UserController 方法级 {@code @PreAuthorize("hasAnyRole('AGENT','ADMIN')")} 控制，
     * 普通 USER 无法拉取全量客服名单。边界：无符合条件者返回空列表。
     *
     * @return 可用的客服 / 管理员脱敏列表
     */
    public List<UserVO> listAgents() {
        return userMapper.selectList(Wrappers.<User>lambdaQuery()
                        .in(User::getRole, List.of("AGENT", "ADMIN"))
                        .eq(User::getStatus, 1)
                        .orderByAsc(User::getId))
                .stream().map(UserVO::from).toList();
    }

    /**
     * 修改目标用户角色，支撑 PUT {@code /api/admin/users/{id}/role}，仅 ADMIN 可调用。
     *
     * <p>数据级校验：角色必须在 ROLES 白名单内，否则抛 badRequest（HTTP 400）；
     * 命中自我操作保护（id 等于 CurrentUser.id()）时拒绝修改自己，防止管理员把自己降级成 USER
     * 导致系统失去唯一管理员。两项校验都在 Service 层，是对路由级 {@code @PreAuthorize} 的补充。
     *
     * <p>{@code @Transactional} 保证校验与更新原子；requireById 不存在时抛 404；返回经 UserVO 脱敏。
     * 异常：非法角色 400、修改自己 400、用户不存在 404。
     *
     * @param id   目标用户 ID
     * @param role 目标角色，取值 USER / AGENT / ADMIN
     * @return 更新后的脱敏用户视图
     */
    @Transactional
    public UserVO updateRole(Long id, String role) {
        if (role == null || !ROLES.contains(role)) {
            // 角色白名单校验：只接受 USER/AGENT/ADMIN，拒绝任意字符串绕过权限体系
            throw BusinessException.badRequest("非法角色");
        }
        if (id.equals(CurrentUser.id())) {
            // 自我操作保护：禁止修改自己的角色，避免把自己降级后系统失去管理员
            throw BusinessException.badRequest("不能修改自己的角色");
        }
        User user = requireById(id);
        user.setRole(role);
        userMapper.updateById(user);
        return UserVO.from(user);
    }

    /**
     * 启用 / 禁用账号，支撑 PUT {@code /api/admin/users/{id}/status}，仅 ADMIN 可调用。
     *
     * <p>数据级校验：status 仅允许 0（禁用）或 1（启用），null 与越界值均抛 badRequest（HTTP 400）；
     * 命中自我操作保护（id 等于 CurrentUser.id()）时拒绝操作自己，避免把自己锁在门外。
     *
     * <p>设计意图：置为 0 后 UserPrincipal.isEnabled() 返回 false，Spring Security 拒绝其登录，
     * JwtAuthFilter 也不再为其建立 SecurityContext，已签发的 Token 随即失效，实现即时踢下线。
     * {@code @Transactional} 保证原子更新；requireById 不存在时抛 404；返回经 UserVO 脱敏。
     * 异常：非法状态 400、禁用自己 400、用户不存在 404。
     *
     * @param id     目标用户 ID
     * @param status 目标状态，1 启用、0 禁用
     * @return 更新后的脱敏用户视图
     */
    @Transactional
    public UserVO updateStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            // 状态白名单校验：仅允许 0 禁用 / 1 启用，null 与越界值一律拒绝
            throw BusinessException.badRequest("非法状态");
        }
        if (id.equals(CurrentUser.id())) {
            // 自我操作保护：禁止禁用当前登录账号，避免把自己锁在门外
            throw BusinessException.badRequest("不能禁用当前登录账号");
        }
        User user = requireById(id);
        user.setStatus(status);
        userMapper.updateById(user);
        return UserVO.from(user);
    }

    /**
     * 统计全部用户数，可用于运营看板等需要展示平台用户总量的场景。
     * <p>selectCount(null) 不带任何条件做全表计数；纯查询、无事务、无权限副作用，返回 long。
     *
     * @return 用户总数
     */
    public long countAll() {
        return userMapper.selectCount(null);
    }
}
