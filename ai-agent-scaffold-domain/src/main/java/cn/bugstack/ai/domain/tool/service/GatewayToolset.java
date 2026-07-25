package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolUserContextEntity;
import cn.bugstack.ai.domain.tool.model.valobj.ToolRuntimeContextKeys;
import cn.bugstack.ai.types.observability.TraceContext;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import io.reactivex.rxjava3.core.Flowable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 每轮模型调用前按可信租户和用户动态解析可用工具。 */
@Component
public class GatewayToolset implements BaseToolset {

    /** 只返回当前用户有权调用的已发布目录项。 */
    private final ToolResolver toolResolver;
    /** 所有目录项共享的执行入口。 */
    private final ToolGateway toolGateway;

    /**
     * 创建网关工具集；参数是工具解析器和工具网关；返回工具集实例。
     */
    public GatewayToolset(ToolResolver toolResolver, ToolGateway toolGateway) {
        this.toolResolver = toolResolver;
        this.toolGateway = toolGateway;
    }

    /** 从只读 state 构造身份与运行回退上下文，再逐项包装为 ADK 工具。 */
    @Override
    public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
        // tenantId 必须来自 ChatService state；userId 可回退 ADK 认证用户。
        ToolUserContextEntity context = ToolUserContextEntity.builder()
                .tenantId(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.TENANT_ID)))
                .userId(defaultString(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.USER_ID)), readonlyContext.userId()))
                .roleCode(stringValue(readonlyContext.state().get("roleCode")))
                .build();
        ToolInvokeContextEntity fallbackContext = ToolInvokeContextEntity.builder()
                .tenantId(context.getTenantId())
                .userId(context.getUserId())
                .sessionId(defaultString(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.SESSION_ID)), readonlyContext.sessionId()))
                .workflowId(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.WORKFLOW_ID)))
                .invocationId(readonlyContext.invocationId())
                .runId(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.RUN_ID)))
                .contextRevision(longValue(readonlyContext.state().get(ToolRuntimeContextKeys.CONTEXT_REVISION)))
                .traceId(defaultString(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.TRACE_ID)), TraceContext.currentOrNewTraceId()))
                .build();
        // 每轮重新查询，发布、停用和权限变化无需重装配 Agent。
        List<ToolCatalogEntity> tools = toolResolver.resolve(context);
        return Flowable.fromIterable(tools).map(tool -> new GatewayAdkTool(tool, toolGateway, fallbackContext));
    }

    /** 包装器不持有连接；MCP 客户端由单次调用创建并关闭。 */
    @Override
    public void close() {
        // 当前工具集不持有长连接，暂不需要释放资源。
    }

    /**
     * 转字符串；参数是对象；返回字符串。
     */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 默认字符串；参数是候选值和默认值；返回非空值。
     */
    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /**
     * 转长整数；参数是对象；返回可空长整数。
     */
    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
