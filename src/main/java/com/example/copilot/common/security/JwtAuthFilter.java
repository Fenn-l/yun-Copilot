package com.example.copilot.common.security;

import com.example.copilot.system.entity.User;
import com.example.copilot.system.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器，继承 {@code OncePerRequestFilter} 保证每个请求只执行一次。
 * 职责：解析 {@code Authorization: Bearer <token>} → 校验签名与过期 → 按 userId 查库取最新用户 →
 * 把 {@link UserPrincipal} 写入 {@code SecurityContext}，供后续鉴权与 {@link CurrentUser} 取值。
 *
 * <p>面试要点（为什么无状态）：服务端不保存 Session，每次请求自带 Token 自证身份，
 * {@code SecurityContext} 仅在本过滤器内临时重建、请求结束即由框架清理，
 * 因此天然支持水平扩容、无需 Session 复制；代价是 Token 在有效期内无法主动失效，
 * 本实现用「每请求回查数据库校验 status 与 role」弥补——角色变更即时生效、停用用户立刻失效。
 *
 * <p>关键规则：仅当 Authorization 头以 Bearer 前缀开头、且当前上下文尚无认证时才处理，
 * 避免覆盖已认证请求（如登录接口）；任一解析 / 查库异常都清空上下文后放行，
 * 由 SecurityConfig 的 authenticationEntryPoint 统一返回 401，过滤器本身不直接写响应。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    /** 负责验签、判过期并解析 Claims。 */
    private final JwtService jwtService;
    /** 按 subject(userId) 回查数据库，拿到最新角色与状态，防止旧 Token 越权。 */
    private final UserMapper userMapper;

    /**
     * 单次过滤逻辑：尝试从 Bearer Token 建立认证，无论成功与否都继续过滤器链。
     * <p>权限边界：本方法只「建立身份」，不做授权判断；鉴权由 SecurityConfig 的
     * authorizeHttpRequests 与方法级 {@code @PreAuthorize} 承担，失败由其入口点写 401 / 403。
     * <p>异常策略：Token 过期、篡改、格式错、查库异常一律捕获并清空上下文，按未认证放行，
     * 绝不让异常冒泡破坏过滤器链，也绝不在半认证状态下继续处理。
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        // 标准 Bearer Token 位于 Authorization 请求头
        String header = request.getHeader("Authorization");
        // 三重条件：有头、Bearer 前缀、且当前线程尚未建立认证（避免覆盖已认证请求）
        if (header != null && header.startsWith("Bearer ") && SecurityContextHolder.getContext().getAuthentication() == null) {
            // 去掉 Bearer 前缀（7 字符）并去首尾空白，得到原始 JWT 字符串
            String token = header.substring(7).trim();
            try {
                // 验签并解析 Claims；签名不符 / 已过期 / 格式错误均抛 JwtException
                Claims claims = jwtService.parse(token);
                // subject 存的是用户 ID 字符串，转回 Long 作为查库主键
                Long userId = Long.valueOf(claims.getSubject());
                // 关键：每请求回查数据库取最新 status / role，角色变更即时生效、停用用户立即失效
                User user = userMapper.selectById(userId);
                // status==1 表示启用；用户不存在或已禁用，即使 Token 有效也不予认证
                if (user != null && Integer.valueOf(1).equals(user.getStatus())) {
                    // 用最新 User 构造主体，保证角色 / 状态是库里的当前值
                    UserPrincipal principal = UserPrincipal.from(user);
                    // 三参构造（principal, credentials=null, authorities）表示「已认证」；
                    // credentials 置 null 避免密码哈希驻留在认证对象中
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                    // 绑定 IP / sessionId 等请求详情，便于审计日志
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    // 写入 SecurityContextHolder（ThreadLocal），后续 @PreAuthorize 与 CurrentUser 都依赖它
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (Exception ignored) {
                // 任何异常都清空上下文，防止半写入的认证对象残留；按「未认证」放行
                SecurityContextHolder.clearContext();
            }
        }
        // 无论是否认证成功都继续过滤器链；是否放行交给 SecurityConfig 的授权规则判定
        filterChain.doFilter(request, response);
    }
}
