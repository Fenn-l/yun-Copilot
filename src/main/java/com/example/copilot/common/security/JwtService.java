package com.example.copilot.common.security;

import com.example.copilot.common.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * JWT 签发与校验。
 * 只把用户 ID 作为 subject，角色仅作展示参考；
 * 鉴权时仍会重新查询数据库，避免角色变更后旧 Token 继续越权。
 *
 * <p>面试要点：使用 jjwt 的 HMAC-SHA 对称签名（算法强度由密钥长度决定），密钥来自
 * {@link AppProperties.Jwt#getSecret()} 的 UTF-8 字节；对称密钥签发与验签都在本服务内完成，
 * 适合单体部署，多服务间共享则需非对称（RS256）或统一密钥治理。
 * Token 只承载 userId（subject）加 username / role 展示信息，不写入权限清单，
 * 权限以每次请求查库为准，杜绝「改了角色旧 Token 仍带旧权限」的越权窗口。
 *
 * <p>无状态含义：服务端不保存 Token 状态，过期时间写进 Claims 的 exp，
 * 由验签时自动判定，过期抛 {@code ExpiredJwtException}，被 {@link JwtAuthFilter}
 * 捕获后按未登录处理，无需服务端黑名单即可自然失效。
 */
@Component
public class JwtService {

    /** 由 secret 构造的 HMAC 密钥；签发与验签同一把，长度需不小于 256 bit 且严格保密。 */
    private final SecretKey key;
    /** 令牌有效期（小时），来自配置，签发时用于计算 exp。 */
    private final int expireHours;

    /**
     * 由配置构造签名密钥与有效期。
     * <p>边界：secret 为空或过短时 {@code Keys.hmacShaKeyFor} 会抛异常，属于启动期
     * 快速失败（fail-fast），避免带弱密钥上线；密钥在 Bean 生命周期内固定，不热更新。
     */
    public JwtService(AppProperties properties) {
        String secret = properties.getJwt().getSecret();
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireHours = properties.getJwt().getExpireHours();
    }

    /**
     * 签发 JWT。
     * <p>规则：subject 仅放 userId；username / role 作为非敏感展示 claim；
     * iat 为当前时间，exp 为 now 加 expireHours 小时；用 HMAC 密钥签名后 compact 成三段式字符串。
     * <p>边界：不含权限 / 敏感信息，故前端可存于内存或 localStorage（仍需权衡 XSS 风险）；
     * JWT 本身无幂等概念，同一秒内多次签发内容相同属正常。
     */
    public String generate(Long userId, String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                // subject 只存用户 ID，是后续查库与 CurrentUser.id() 的唯一依据
                .subject(String.valueOf(userId))
                // 展示性 claim，方便前端显示，不参与鉴权判断
                .claim("username", username)
                .claim("role", role)
                // iat 签发时间，便于审计与问题排查
                .issuedAt(Date.from(now))
                // exp 过期时间 = 当前 + expireHours；验签时 jjwt 自动校验，过期即抛异常
                .expiration(Date.from(now.plus(expireHours, ChronoUnit.HOURS)))
                // 用对称密钥签名，防篡改
                .signWith(key)
                .compact();
    }

    /**
     * 校验并解析 Token。
     * <p>关键：{@code verifyWith(key)} 先验签再取 Claims，jjwt 会自动校验 exp，
     * 过期 / 篡改 / 算法不符均抛异常，由 {@link JwtAuthFilter} 统一吞掉并清空上下文；
     * 解析失败不会返回 null，而是以异常形式表达，避免调用方误判为合法请求。
     * <p>职责单一：只返回 Claims 供取 subject(userId)，不在此查库、不做权限判断。
     */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
