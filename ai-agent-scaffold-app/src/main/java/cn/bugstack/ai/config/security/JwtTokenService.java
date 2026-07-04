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

    private final JwtProperties jwtProperties;

    public JwtTokenService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @Override
    public String generateToken(LoginUser loginUser) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(jwtProperties.getExpireSeconds());
        return JWT.create()
                .withIssuer(jwtProperties.getIssuer())
                .withSubject(loginUser.getUserId())
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(expiresAt))
                .withClaim("tenantId", loginUser.getTenantId())
                .withClaim("userId", loginUser.getUserId())
                .withClaim("username", loginUser.getUsername())
                .withClaim("roleCode", loginUser.getRoleCode())
                .sign(algorithm());
    }

    @Override
    public long expireSeconds() {
        return jwtProperties.getExpireSeconds();
    }

    public LoginUser parseToken(String token) {
        JWTVerifier verifier = JWT.require(algorithm())
                .withIssuer(jwtProperties.getIssuer())
                .build();
        DecodedJWT decodedJWT = verifier.verify(token);
        return LoginUser.builder()
                .tenantId(decodedJWT.getClaim("tenantId").asString())
                .userId(decodedJWT.getClaim("userId").asString())
                .username(decodedJWT.getClaim("username").asString())
                .roleCode(decodedJWT.getClaim("roleCode").asString())
                .build();
    }

    private Algorithm algorithm() {
        return Algorithm.HMAC256(jwtProperties.getSecret());
    }
}
