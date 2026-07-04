package cn.bugstack.ai.types.context;

import org.slf4j.MDC;

public final class TenantContextHolder {

    public static final String TENANT_ID_MDC_KEY = "tenantId";
    public static final String USER_ID_MDC_KEY = "userId";

    private static final ThreadLocal<TenantContext> CONTEXT = new ThreadLocal<>();

    /**
     * 禁止创建工具类实例；无参数；无返回值。
     */
    private TenantContextHolder() {
    }

    /**
     * 设置当前身份；参数是租户上下文；无返回值。
     */
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

    /**
     * 获取当前身份；无参数；返回租户上下文。
     */
    public static TenantContext get() {
        return CONTEXT.get();
    }

    /**
     * 获取当前租户ID；无参数；返回 tenantId。
     */
    public static String getTenantId() {
        TenantContext context = CONTEXT.get();
        return context == null ? null : context.getTenantId();
    }

    /**
     * 获取当前用户ID；无参数；返回 userId。
     */
    public static String getUserId() {
        TenantContext context = CONTEXT.get();
        return context == null ? null : context.getUserId();
    }

    /**
     * 获取当前角色编码；无参数；返回 roleCode。
     */
    public static String getRoleCode() {
        TenantContext context = CONTEXT.get();
        return context == null ? null : context.getRoleCode();
    }

    /**
     * 清理当前身份；无参数；无返回值。
     */
    public static void clear() {
        CONTEXT.remove();
        MDC.remove(TENANT_ID_MDC_KEY);
        MDC.remove(USER_ID_MDC_KEY);
    }
}
