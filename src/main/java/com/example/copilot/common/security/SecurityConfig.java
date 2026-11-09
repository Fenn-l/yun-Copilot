package com.example.copilot.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 主配置，串起无状态 JWT 鉴权链路。
 *
 * <p>面试要点：
 * <ul>
 *   <li>{@code @EnableWebSecurity} 启用 Web 安全；{@code @EnableMethodSecurity} 开启
 *       {@code @PreAuthorize} / {@code @Secured} 方法级鉴权，ADMIN / AGENT 专属接口据此拦截。</li>
 *   <li>{@code SessionCreationPolicy.STATELESS} 彻底禁用 HttpSession，配合 JWT 实现无状态，
 *       SecurityContext 不跨请求保留，由 {@link JwtAuthFilter} 每请求重建。</li>
 *   <li>CSRF 对无状态 REST API 关闭（不依赖 Cookie），CORS 放行本地前端并暴露 Authorization 头。</li>
 *   <li>白名单：OPTIONS 预检、/api/auth/**（登录注册）、/error、以及 SPA 静态资源放行；
 *       其余 anyRequest 必须 authenticated。URL 到角色的细粒度规则用 {@code @PreAuthorize}
 *       放在方法上，数据级权限再下沉到 Service 用 {@link CurrentUser} 校验，
 *       形成「认证 → 方法授权 → 数据鉴权」三层。</li>
 *   <li>认证 / 授权失败不抛到全局异常处理器，而是由 authenticationEntryPoint /
 *       accessDeniedHandler 直接写 JSON（401 / 403），保证未被 Controller 接管的请求也有统一响应体。</li>
 *   <li>{@code addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)}
 *       把 JWT 过滤器插在表单登录过滤器之前，先于授权判断建立上下文。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** 自定义 JWT 过滤器，注入到过滤器链中。 */
    private final JwtAuthFilter jwtAuthFilter;
    /** 供 DaoAuthenticationProvider 在登录时按用户名查库并校验密码。 */
    private final SecurityUserDetailsService userDetailsService;

    /**
     * 注册 BCrypt 密码编码器：注册与校验共用，带随机盐、不可逆，库里只存哈希。
     * <p>强度由 BCrypt 默认 cost=10 决定，抗彩虹表；明文密码绝不落库。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 登录用的 DaoAuthenticationProvider：委托 userDetailsService 取用户、用 passwordEncoder 比对密码。
     * <p>只处理用户名密码登录；JWT 请求由 {@link JwtAuthFilter} 负责，不经过此 Provider。
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(passwordEncoder());
        provider.setUserDetailsService(userDetailsService);
        return provider;
    }

    /**
     * 暴露 AuthenticationManager，供登录接口（/api/auth/login）手动触发认证。
     * <p>由 AuthenticationConfiguration 组装，内部会用到上面的 Provider 与 PasswordEncoder。
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * 构建安全过滤器链：白名单放行、其余需认证、无状态会话、异常入口点直写统一 JSON、JWT 过滤器前置。
     * <p>任何认证 / 授权失败都在本链内被入口点拦截，绝不进入 Controller；
     * 这是与 GlobalExceptionHandler 的分工边界（过滤器层异常进不了 MVC 的 @RestControllerAdvice）。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 无状态 Token 不依赖 Cookie，关闭 CSRF；若改用 Cookie 承载凭证需重新开启
                .csrf(csrf -> csrf.disable())
                // 使用本类 corsConfigurationSource() 定义的跨域规则
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // 核心：STATELESS 不创建 / 不使用 HttpSession，SecurityContext 每请求由 JWT 过滤器重建
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 放行 CORS 预检请求，否则跨域非简单请求会先被 401 挡下
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 登录 / 注册等认证端点与错误回退端点免认证；/error 放行避免异常时被二次 401 掩盖
                        .requestMatchers("/api/auth/**", "/error").permitAll()
                        // Vue 3 打包产物（SPA）与站点图标放行，交给前端路由
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico", "/favicon.svg",
                                "/logo.svg", "/robots.txt").permitAll()
                        // 兜底：其余所有 API 必须携带有效 JWT
                        .anyRequest().authenticated())
                .exceptionHandling(handler -> handler
                        // 未认证 / Token 缺失或过期 → 401，直接写 JSON（不经过 @RestControllerAdvice）
                        .authenticationEntryPoint((request, response, ex) ->
                                writeJson(response, 401, "未登录或登录已过期"))
                        // 已认证但权限不足 → 403，同理直写 JSON
                        .accessDeniedHandler((request, response, ex) ->
                                writeJson(response, 403, "没有权限执行该操作")))
                // 注册登录用的认证提供者
                .authenticationProvider(authenticationProvider())
                // JWT 过滤器置于表单登录过滤器之前，授权判断前先建好 SecurityContext
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 认证入口点 / 拒绝处理器共用的响应写出方法，手工拼 code / message / data 三段式 JSON，
     * 与 ApiResponse 结构保持一致。
     * <p>边界：本方法在过滤器层执行，早于 Spring MVC，无法用 @RestControllerAdvice 或消息转换器，
     * 故直接写响应流；message 为固定常量、不含外部输入，避免 JSON 注入。
     */
    private void writeJson(jakarta.servlet.http.HttpServletResponse response, int code, String message)
            throws java.io.IOException {
        response.setStatus(code);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        // 下一行手工拼与 ApiResponse 一致的 code/message/data 结构，保证前端统一解析
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}");
    }

    /**
     * 跨域配置：允许本地前端任意端口、常见方法、任意请求头，暴露 Authorization 响应头，
     * 允许携带凭证，预检结果缓存 1 小时。
     * <p>边界：allowedOriginPatterns 用通配端口适配本地多端口开发；与 allowCredentials=true
     * 配合时不能用 "*" 精确原点；生产应收紧到具体域名。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // 仅放行本地前端任意端口，避免任意来源携带凭证访问
        configuration.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        // 放行 REST 常用方法与预检 OPTIONS
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        // 允许任意请求头（含 Authorization）
        configuration.setAllowedHeaders(List.of("*"));
        // 暴露 Authorization 响应头，便于前端读取（如令牌刷新场景）
        configuration.setExposedHeaders(List.of("Authorization"));
        // 允许携带凭证；与通配原点配合时须用 AllowedOriginPatterns 而非 AllowedOrigins
        configuration.setAllowCredentials(true);
        // 预检结果缓存 1 小时，减少 OPTIONS 往返
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // 对全部路径应用该跨域配置
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
