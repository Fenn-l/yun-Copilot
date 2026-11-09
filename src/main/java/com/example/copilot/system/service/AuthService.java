package com.example.copilot.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.copilot.common.exception.BusinessException;
import com.example.copilot.common.security.JwtService;
import com.example.copilot.common.security.UserPrincipal;
import com.example.copilot.system.dto.AuthResponse;
import com.example.copilot.system.dto.LoginRequest;
import com.example.copilot.system.dto.RegisterRequest;
import com.example.copilot.system.dto.UserVO;
import com.example.copilot.system.entity.User;
import com.example.copilot.system.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证核心服务，承载注册、登录与「取当前登录人」三条链路，是 JWT 无状态鉴权体系的业务入口。
 *
 * <p>设计要点：服务端不保存会话，登录 / 注册成功后直接签发 JWT，之后每次请求由 JwtAuthFilter
 * 解析 Token 并回查 cs_user 重建登录上下文；本类只负责「发 Token」与「读上下文」，不持有状态。
 * {@code @RequiredArgsConstructor} 由 Lombok 为四个 final 依赖生成构造器注入。
 *
 * <p>安全约束：注册固定赋予 USER 角色、密码一律 BCrypt 加密、所有出参经 UserVO 脱敏，
 * 从入口上杜绝越权提权、明文口令与密码哈希外泄。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    /**
     * 用户表 Mapper，负责判重、落库与按 ID 回查。
     */
    private final UserMapper userMapper;
    /**
     * Spring Security 的密码编码器（实际为 BCrypt），用于注册加密与登录比对。
     */
    private final PasswordEncoder passwordEncoder;
    /**
     * 认证管理器，登录时委托 DaoAuthenticationProvider 完成查库、密码比对与账号可用性检查。
     */
    private final AuthenticationManager authenticationManager;
    /**
     * JWT 签发工具，载荷仅含用户 ID / 用户名 / 角色，不携带任何敏感信息。
     */
    private final JwtService jwtService;

    /**
     * 注册新用户并直接签发 JWT，实现「注册即登录」。
     *
     * <p>关键规则：
     * <ul>
     *   <li>唯一性：先按 username 做 selectCount 判重，命中则抛 BusinessException.conflict（HTTP 409）。</li>
     *   <li>密码：用 passwordEncoder.encode 做 BCrypt 加密，数据库永不保存明文，出参也不含哈希。</li>
     *   <li>角色：固定 setRole("USER")、setStatus(1)，忽略前端任何角色 / 状态参数，防止注册接口越权提权。</li>
     *   <li>昵称：为空时兜底为用户名；insert 后由 MyBatis-Plus 回填自增 ID，供签发 Token。</li>
     * </ul>
     *
     * <p>{@code @Transactional} 保证判重与插入位于同一事务；返回 AuthResponse 中的用户信息经 UserVO 脱敏。
     * 边界与异常：参数格式由 Controller 的 {@code @Valid} 先行校验；用户名重复抛 409；
     * 应用层判重存在并发窗口，杜绝并发同名最终需 cs_user.username 的数据库唯一约束配合。
     *
     * @param request 注册资料（用户名、密码、可选昵称与邮箱）
     * @return JWT 与脱敏用户视图
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        Long exists = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, request.getUsername()));
        if (exists != null && exists > 0) {
            // 用户名唯一约束：命中即说明已注册，抛 409 冲突，避免产生同名账号
            throw BusinessException.conflict("用户名已存在");
        }
        User user = new User();
        user.setUsername(request.getUsername());
        // BCrypt 加密：明文密码只在此处被消费一次，落库与后续任何出参都不可逆、不可见
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        // 昵称可选：为空时兜底为用户名，保证展示字段始终有值
        user.setNickname(request.getNickname() == null || request.getNickname().isBlank()
                ? request.getUsername() : request.getNickname());
        user.setEmail(request.getEmail());
        // 注册固定赋予 USER + 启用，忽略前端角色参数，杜绝借注册接口自我提权
        user.setRole("USER");
        user.setStatus(1);
        // 插入后自增主键回填到 user.id，签发 JWT 时以其作为 subject
        userMapper.insert(user);
        // 以刚落库的最新 id / username / role 签发 Token，角色取自库而非前端
        String token = jwtService.generate(user.getId(), user.getUsername(), user.getRole());
        return new AuthResponse(token, UserVO.from(user));
    }

    /**
     * 校验用户名密码并签发 JWT。
     *
     * <p>关键规则：authenticationManager.authenticate 会触发完整认证链——
     * SecurityUserDetailsService 按用户名查库返回 UserPrincipal，PasswordEncoder 以 BCrypt 比对密码，
     * UserPrincipal.isEnabled() 据 status 判定账号可用（status 为 0 的禁用账号直接拒绝登录）。
     * 认证成功后 principal 即 UserPrincipal，取其 id 再回查一次完整 User，确保用最新库数据签发。
     *
     * <p>边界与异常：密码错误 / 账号禁用由 Spring Security 抛认证异常并交全局异常处理映射为错误响应；
     * 若认证通过却按 ID 查不到用户（如并发删除），抛 BusinessException.badRequest（HTTP 400）；
     * 签发用的 role 取自数据库，因此角色变更对后续登录即时生效。返回用户信息经 UserVO 脱敏。
     *
     * @param request 登录凭据（用户名、明文密码）
     * @return JWT 与脱敏用户视图
     */
    public AuthResponse login(LoginRequest request) {
        // 委托 Spring Security 认证链：查库 + BCrypt 密码比对 + 账号启用状态检查
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        User user = userMapper.selectById(principal.getId());
        if (user == null) {
            // 认证已通过却查不到实体（并发删除等），显式失败，避免后续签发与脱敏时空指针
            throw BusinessException.badRequest("用户不存在");
        }
        // 角色取自实时库数据，保证降级 / 升权后的 Token 声明与数据库一致
        String token = jwtService.generate(user.getId(), user.getUsername(), user.getRole());
        return new AuthResponse(token, UserVO.from(user));
    }

    /**
     * 取当前登录人的脱敏信息，支撑 GET {@code /api/auth/me}。
     *
     * <p>如何取当前登录人：方法不接收任何参数，登录人完全来自 SecurityContextHolder 中
     * JwtAuthFilter 预置的 Authentication。链路为：请求头 Authorization: Bearer Token →
     * 解析出 subject（用户 ID）→ 按 ID 回查 cs_user 并校验 status=1 → 构造 UserPrincipal 写入上下文。
     * 本方法从 principal.getId() 再查一次最新 User 并转 UserVO，保证角色 / 状态变更后返回最新值且不含密码哈希。
     *
     * <p>边界与异常：SecurityConfig 对 {@code /api/auth/**} 整体放行，但未携带有效 Token 时上下文为匿名主体，
     * 无法取得 UserPrincipal，强转会失败；因此该接口业务上要求已登录，前端须登录后携带 Bearer Token 调用。
     *
     * @return 当前登录用户的脱敏视图
     */
    public UserVO me() {
        // 当前登录人取自 JwtAuthFilter 写入的 SecurityContext，而非请求参数，杜绝前端伪造身份
        UserPrincipal principal = (UserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return UserVO.from(userMapper.selectById(principal.getId()));
    }
}
