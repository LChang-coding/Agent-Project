package cn.bugstack.ai.config;

import cn.bugstack.ai.config.security.AuthFilter;
import cn.bugstack.ai.types.context.TenantContext;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.observability.AiLog;
import cn.bugstack.ai.types.observability.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceContext.normalizeOrNew(request.getHeader(TraceContext.TRACE_ID_HEADER));

        long start = System.currentTimeMillis();
        Throwable failure = null;
        try {
            TraceContext.setTraceId(traceId);
            response.setHeader(TraceContext.TRACE_ID_HEADER, traceId);
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            logRequest(request, response, start, failure);
            TraceContext.clear();
        }
    }

    private void logRequest(HttpServletRequest request,
                            HttpServletResponse response,
                            long start,
                            Throwable failure) {
        long costMs = System.currentTimeMillis() - start;
        int status = response.getStatus();
        boolean success = failure == null && status < 400;
        restoreTenantContext(request);
        try {
            if (success) {
                AiLog.info(AiLog.http().request(request.getMethod(), request.getRequestURI(), status, costMs, true));
            } else {
                AiLog.error(AiLog.http().error(request.getMethod(), request.getRequestURI(), status, costMs, failure));
            }
        } finally {
            TenantContextHolder.clear();
        }
    }

    private void restoreTenantContext(HttpServletRequest request) {
        Object tenantId = request.getAttribute(AuthFilter.REQUEST_ATTR_TENANT_ID);
        Object userId = request.getAttribute(AuthFilter.REQUEST_ATTR_USER_ID);
        if (tenantId == null || userId == null) {
            return;
        }
        TenantContextHolder.set(TenantContext.builder()
                .tenantId(String.valueOf(tenantId))
                .userId(String.valueOf(userId))
                .username(stringAttribute(request, AuthFilter.REQUEST_ATTR_USERNAME))
                .roleCode(stringAttribute(request, AuthFilter.REQUEST_ATTR_ROLE_CODE))
                .build());
    }

    private String stringAttribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value == null ? null : String.valueOf(value);
    }
}
