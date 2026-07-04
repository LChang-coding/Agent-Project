package cn.bugstack.ai.config.security;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.types.context.LoginUser;
import cn.bugstack.ai.types.context.TenantContext;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class AuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    public static final String REQUEST_ATTR_TENANT_ID = "auth.tenantId";
    public static final String REQUEST_ATTR_USER_ID = "auth.userId";
    public static final String REQUEST_ATTR_USERNAME = "auth.username";
    public static final String REQUEST_ATTR_ROLE_CODE = "auth.roleCode";

    private final JwtTokenService jwtTokenService;
    private final ObjectMapper objectMapper;

    public AuthFilter(JwtTokenService jwtTokenService, ObjectMapper objectMapper) {
        this.jwtTokenService = jwtTokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.equals("/api/v1/auth/register")
                || uri.equals("/api/v1/auth/login")
                || uri.equals("/error")
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!authorization.startsWith(BEARER_PREFIX)) {
            writeUnauthorized(response);
            return;
        }

        try {
            LoginUser loginUser = jwtTokenService.parseToken(authorization.substring(BEARER_PREFIX.length()));
            request.setAttribute(REQUEST_ATTR_TENANT_ID, loginUser.getTenantId());
            request.setAttribute(REQUEST_ATTR_USER_ID, loginUser.getUserId());
            request.setAttribute(REQUEST_ATTR_USERNAME, loginUser.getUsername());
            request.setAttribute(REQUEST_ATTR_ROLE_CODE, loginUser.getRoleCode());
            TenantContextHolder.set(TenantContext.builder()
                    .tenantId(loginUser.getTenantId())
                    .userId(loginUser.getUserId())
                    .username(loginUser.getUsername())
                    .roleCode(loginUser.getRoleCode())
                    .build());

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    loginUser,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + loginUser.getRoleCode().toUpperCase()))
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            SecurityContextHolder.clearContext();
            TenantContextHolder.clear();
            writeUnauthorized(response);
        } finally {
            SecurityContextHolder.clearContext();
            TenantContextHolder.clear();
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Response<Void> body = Response.<Void>builder()
                .code(ResponseCode.AUTH_UNAUTHORIZED.getCode())
                .info(ResponseCode.AUTH_UNAUTHORIZED.getInfo())
                .build();
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
