package com.example.copilot.common.security;

import com.example.copilot.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录人读取工具，从 {@code SecurityContextHolder} 取出 {@link UserPrincipal}，
 * 供 Service 层做数据级权限校验（如 USER 只能访问自己的会话 / 工单）。
 *
 * <p>面试要点：SecurityContextHolder 默认 {@code MODE_THREADLOCAL}，认证对象由
 * {@link JwtAuthFilter} 在请求进入时写入当前线程、请求结束由框架清理；
 * 因此本类只是对「线程绑定上下文」的静态封装，而非自建 ThreadLocal。
 * 取值时机有强约束：只能在「HTTP 请求线程且过滤器已执行之后」调用，
 * {@code @Async} 子线程（如 docExecutor）默认继承不到该上下文，需显式传递用户 ID；
 * 在 Bean 构造 / 初始化阶段调用会因上下文为空直接抛 401。
 *
 * <p>设计意图：JWT 无状态并不意味着要把 userId 层层透传方法签名，
 * 借助线程上下文在 Service 内随取随用，既保持无状态又简化数据鉴权代码。
 */
public final class CurrentUser {

    /** 私有构造器：纯静态工具类，禁止被实例化。 */
    private CurrentUser() {
    }

    /**
     * 取当前登录主体；无认证或 principal 不是 {@link UserPrincipal} 时抛 401。
     * <p>边界：用 instanceof 模式匹配同时完成类型校验与变量绑定，兼容匿名 / 未认证
     * （principal 为 anonymousUser 字符串）场景；抛出的 401 由全局异常处理器统一映射。
     */
    public static UserPrincipal get() {
        // 从线程绑定的 SecurityContext 取认证对象；过滤器尚未执行时为 null
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // 仅认 UserPrincipal 类型的主体，排除 anonymousUser 等未认证占位值
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            return principal;
        }
        throw new BusinessException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
    }

    /** 快捷取用户 ID，常作为按 ownerId / userId 过滤数据的入参，是数据级权限查询的起点。 */
    public static Long id() {
        return get().getId();
    }

    /** 是否 ADMIN；用于放行全量数据与管理端操作。 */
    public static boolean isAdmin() {
        return get().isAdmin();
    }

    /**
     * 是否可跨用户管理全部数据：AGENT 或 ADMIN（复用 {@link UserPrincipal#isAgent()}）。
     * <p>与 isAdmin 的区别：AGENT 即可处理他人会话 / 工单，但不含系统管理权限；
     * 方法名刻意表达「数据范围」而非「角色等级」，供 Service 决定查询范围。
     */
    public static boolean canManageAll() {
        return get().isAgent();
    }
}
