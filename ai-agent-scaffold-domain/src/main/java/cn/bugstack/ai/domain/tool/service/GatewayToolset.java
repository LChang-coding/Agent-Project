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

/**
 * ADK 网关工具集。
 * <p>负责在每轮模型调用前动态查询当前用户有权限的工具，并包装成 ADK 工具。</p>
 */
@Component
public class GatewayToolset implements BaseToolset {

    private final ToolResolver toolResolver;
    private final ToolGateway toolGateway;

    /**
     * 创建网关工具集；参数是工具解析器和工具网关；返回工具集实例。
     */
    public GatewayToolset(ToolResolver toolResolver, ToolGateway toolGateway) {
        this.toolResolver = toolResolver;
        this.toolGateway = toolGateway;
    }

    /**
     * 获取本轮可用工具；参数是 ADK 只读上下文；返回工具流。
     */
    @Override
    public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
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
        List<ToolCatalogEntity> tools = toolResolver.resolve(context);
        return Flowable.fromIterable(tools).map(tool -> new GatewayAdkTool(tool, toolGateway, fallbackContext));
    }

    /**
     * 关闭工具集；无参数；无返回值。
     */
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
