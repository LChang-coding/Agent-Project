package cn.bugstack.ai.config.security;

import cn.bugstack.ai.domain.auth.service.IJwtTokenService;
import cn.bugstack.ai.types.context.LoginUser;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;

@Service
public class JwtTokenService implements IJwtTokenService {

    private static final String CLAIM_TOKEN_TYPE = "tokenType";
    private static final String TOKEN_TYPE_ACCESS = "access";
    private static final String TOKEN_TYPE_REFRESH = "refresh";

    private final JwtProperties jwtProperties;

    /**
     * 创建 JWT 服务；参数是 JWT 配置；返回服务实例。
     */
    public JwtTokenService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    /**
     * 生成访问令牌；参数是登录用户；返回 access token。
     */
    @Override
    public String generateToken(LoginUser loginUser) {
        return generate(loginUser, TOKEN_TYPE_ACCESS, jwtProperties.getExpireSeconds());
    }

    /**
     * 生成刷新令牌；参数是登录用户；返回 refresh token。
     */
    @Override
    public String generateRefreshToken(LoginUser loginUser) {
        return generate(loginUser, TOKEN_TYPE_REFRESH, jwtProperties.getRefreshExpireSeconds());
    }

    /**
     * 读取访问令牌有效期；无参数；返回秒数。
     */
    @Override
    public long expireSeconds() {
        return jwtProperties.getExpireSeconds();
    }

    /**
     * 读取刷新令牌有效期；无参数；返回秒数。
     */
    @Override
    public long refreshExpireSeconds() {
        return jwtProperties.getRefreshExpireSeconds();
    }

    /**
     * 解析访问令牌；参数是 access token；返回登录用户。
     */
    @Override
    public LoginUser parseToken(String token) {
        return parse(token, TOKEN_TYPE_ACCESS);
    }

    /**
     * 解析刷新令牌；参数是 refresh token；返回登录用户。
     */
    @Override
    public LoginUser parseRefreshToken(String token) {
        return parse(token, TOKEN_TYPE_REFRESH);
    }

    /**
     * 生成指定类型令牌；参数是登录用户、令牌类型和有效期；返回 JWT 字符串。
     */
    private String generate(LoginUser loginUser, String tokenType, long expireSeconds) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(expireSeconds);
        return JWT.create()
                .withIssuer(jwtProperties.getIssuer())
                .withSubject(loginUser.getUserId())
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(expiresAt))
                .withClaim(CLAIM_TOKEN_TYPE, tokenType)
                .withClaim("tenantId", loginUser.getTenantId())
                .withClaim("userId", loginUser.getUserId())
                .withClaim("username", loginUser.getUsername())
                .withClaim("roleCode", loginUser.getRoleCode())
                .sign(algorithm());
    }

    /**
     * 解析指定类型令牌；参数是 JWT 和期望类型；返回登录用户。
     */
    private LoginUser parse(String token, String expectedTokenType) {
        JWTVerifier verifier = JWT.require(algorithm())
                .withIssuer(jwtProperties.getIssuer())
                .build();
        DecodedJWT decodedJWT = verifier.verify(token);
        if (!expectedTokenType.equals(decodedJWT.getClaim(CLAIM_TOKEN_TYPE).asString())) {
            throw new IllegalArgumentException("token type mismatch");
        }
        return LoginUser.builder()
                .tenantId(decodedJWT.getClaim("tenantId").asString())
                .userId(decodedJWT.getClaim("userId").asString())
                .username(decodedJWT.getClaim("username").asString())
                .roleCode(decodedJWT.getClaim("roleCode").asString())
                .build();
    }

    /**
     * 创建签名算法；无参数；返回 HMAC256 算法。
     */
    private Algorithm algorithm() {
        return Algorithm.HMAC256(jwtProperties.getSecret());
    }
}
