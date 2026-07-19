package cn.bugstack.ai.test.config;

import cn.bugstack.ai.config.security.AuthFilter;
import cn.bugstack.ai.config.security.JwtProperties;
import cn.bugstack.ai.config.security.JwtTokenService;
import cn.bugstack.ai.types.context.LoginUser;
import cn.bugstack.ai.types.context.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AuthFilterTest {

    private JwtTokenService jwtTokenService;
    private AuthFilter authFilter;

    @Before
    public void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("auth-filter-test-secret-that-is-long-enough-for-hmac");
        properties.setIssuer("auth-filter-test");
        properties.setExpireSeconds(3600L);
        jwtTokenService = new JwtTokenService(properties);
        authFilter = new AuthFilter(jwtTokenService, new ObjectMapper());
    }

    @After
    public void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    @Test
    public void shouldReturnUnauthorizedForInvalidTokenWithoutInvokingDownstream() throws Exception {
        MockHttpServletRequest request = request("not-a-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        authFilter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertEquals(401, response.getStatus());
        assertFalse(invoked.get());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(TenantContextHolder.get());
    }

    @Test
    public void shouldPropagateDownstreamExceptionInsteadOfRewritingItAsUnauthorized() throws Exception {
        MockHttpServletRequest request = request(accessToken());
        MockHttpServletResponse response = new MockHttpServletResponse();

        ServletException exception;
        try {
            authFilter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
                assertTrue(SecurityContextHolder.getContext().getAuthentication().isAuthenticated());
                assertEquals("tenant-1", TenantContextHolder.getTenantId());
                throw new ServletException("downstream database unavailable");
            });
            fail("downstream exception must propagate");
            return;
        } catch (ServletException expected) {
            exception = expected;
        }

        assertEquals("downstream database unavailable", exception.getMessage());
        assertEquals(200, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(TenantContextHolder.get());
    }

    @Test
    public void shouldClearContextsAfterSuccessfulDownstreamInvocation() throws ServletException, IOException {
        MockHttpServletRequest request = request(accessToken());
        MockHttpServletResponse response = new MockHttpServletResponse();

        authFilter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            assertEquals("tenant-1", request.getAttribute(AuthFilter.REQUEST_ATTR_TENANT_ID));
            assertEquals("user-1", request.getAttribute(AuthFilter.REQUEST_ATTR_USER_ID));
            assertEquals("owner", request.getAttribute(AuthFilter.REQUEST_ATTR_ROLE_CODE));
            assertEquals("tenant-1", TenantContextHolder.getTenantId());
        });

        assertEquals(200, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(TenantContextHolder.get());
    }

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/rag/retrieval-debug");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return request;
    }

    private String accessToken() {
        return jwtTokenService.generateToken(LoginUser.builder()
                .tenantId("tenant-1")
                .userId("user-1")
                .username("benchmark")
                .roleCode("owner")
                .build());
    }
}
