package cn.bugstack.ai.types.context;

import org.slf4j.MDC;

public final class TenantContextHolder {

    public static final String TENANT_ID_MDC_KEY = "tenantId";
    public static final String USER_ID_MDC_KEY = "userId";

    private static final ThreadLocal<TenantContext> CONTEXT = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    public static void set(TenantContext context) {
        if (context == null) {
            clear();
            return;
        }
        CONTEXT.set(context);
        if (context.getTenantId() != null && !context.getTenantId().isBlank()) {
            MDC.put(TENANT_ID_MDC_KEY, context.getTenantId());
        }
        if (context.getUserId() != null && !context.getUserId().isBlank()) {
            MDC.put(USER_ID_MDC_KEY, context.getUserId());
        }
    }

    public static TenantContext get() {
        return CONTEXT.get();
    }

    public static String getTenantId() {
        TenantContext context = CONTEXT.get();
        return context == null ? null : context.getTenantId();
    }

    public static String getUserId() {
        TenantContext context = CONTEXT.get();
        return context == null ? null : context.getUserId();
    }

    public static String getRoleCode() {
        TenantContext context = CONTEXT.get();
        return context == null ? null : context.getRoleCode();
    }

    public static void clear() {
        CONTEXT.remove();
        MDC.remove(TENANT_ID_MDC_KEY);
        MDC.remove(USER_ID_MDC_KEY);
    }
}
