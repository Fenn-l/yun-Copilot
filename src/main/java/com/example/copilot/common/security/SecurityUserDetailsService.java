package com.example.copilot.common.security;

import com.example.copilot.system.entity.User;
import com.example.copilot.system.mapper.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 登录认证用的 {@code UserDetailsService} 实现：按用户名查库，交给
 * {@code DaoAuthenticationProvider} 完成密码比对。
 *
 * <p>面试要点：只服务于「用户名密码登录」这一条路径（/api/auth/login）；
 * JWT 请求不经过这里，而是由 {@link JwtAuthFilter} 按 userId 查库直接重建主体。
 * 查不到用户抛 {@code UsernameNotFoundException}，由 Spring Security 转成 401，
 * 对外统一模糊文案，避免用户名枚举；返回 {@link UserPrincipal} 使主体带角色与状态。
 */
@Service
@RequiredArgsConstructor
public class SecurityUserDetailsService implements UserDetailsService {

    /** 按用户名查询用户，供登录认证取数与密码比对。 */
    private final UserMapper userMapper;

    /**
     * 按用户名精确查询用户并包装为 UserDetails。
     * <p>规则：用 MyBatis-Plus lambdaQuery 按 username 等值查询；不存在则抛
     * {@code UsernameNotFoundException}（由 Security 转 401）。
     * <p>边界：账户启停（status）不在本方法拦截，交由 {@link UserPrincipal#isEnabled()}
     * 与过滤器 status==1 校验；username 有唯一索引保证至多一条；
     * 密码比对由 DaoAuthenticationProvider 用 BCrypt 完成，本方法不接触明文密码。
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // lambdaQuery 按用户名等值查询，依赖 username 唯一索引，避免返回多条
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
        // 查无此人抛 UsernameNotFoundException，由 Security 统一转 401 且对外模糊提示
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        return UserPrincipal.from(user);
    }
}
