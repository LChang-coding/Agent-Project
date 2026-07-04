package cn.bugstack.ai.test.config;

import cn.bugstack.ai.config.security.JwtProperties;
import cn.bugstack.ai.config.security.JwtTokenService;
import cn.bugstack.ai.types.context.LoginUser;
import org.junit.Assert;
import org.junit.Test;

public class JwtTokenServiceTest {

    /**
     * 校验访问令牌；无参数；验证 access token 可以解析出用户身份。
     */
    @Test
    public void shouldParseAccessToken() {
        JwtTokenService jwtTokenService = createJwtTokenService();
        LoginUser loginUser = createLoginUser();

        LoginUser actual = jwtTokenService.parseToken(jwtTokenService.generateToken(loginUser));

        Assert.assertEquals("tenant_1", actual.getTenantId());
        Assert.assertEquals("user_1", actual.getUserId());
        Assert.assertEquals("codeliu", actual.getUsername());
        Assert.assertEquals("owner", actual.getRoleCode());
    }

    /**
     * 校验刷新令牌；无参数；验证 refresh token 可以解析出用户身份。
     */
    @Test
    public void shouldParseRefreshToken() {
        JwtTokenService jwtTokenService = createJwtTokenService();
        LoginUser loginUser = createLoginUser();

        LoginUser actual = jwtTokenService.parseRefreshToken(jwtTokenService.generateRefreshToken(loginUser));

        Assert.assertEquals("tenant_1", actual.getTenantId());
        Assert.assertEquals("user_1", actual.getUserId());
        Assert.assertEquals("codeliu", actual.getUsername());
        Assert.assertEquals("owner", actual.getRoleCode());
    }

    /**
     * 校验令牌类型隔离；无参数；验证 refresh token 不能当 access token 使用。
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectRefreshTokenAsAccessToken() {
        JwtTokenService jwtTokenService = createJwtTokenService();

        jwtTokenService.parseToken(jwtTokenService.generateRefreshToken(createLoginUser()));
    }

    /**
     * 创建测试 JWT 服务；无参数；返回固定配置的服务。
     */
    private JwtTokenService createJwtTokenService() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setIssuer("ai-agent-scaffold-test");
        jwtProperties.setSecret("unit-test-secret-ai-agent-scaffold");
        jwtProperties.setExpireSeconds(7200L);
        jwtProperties.setRefreshExpireSeconds(2592000L);
        return new JwtTokenService(jwtProperties);
    }

    /**
     * 创建测试登录用户；无参数；返回固定用户身份。
     */
    private LoginUser createLoginUser() {
        return LoginUser.builder()
                .tenantId("tenant_1")
                .userId("user_1")
                .username("codeliu")
                .roleCode("owner")
                .build();
    }
}
