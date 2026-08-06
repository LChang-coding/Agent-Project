package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PlatformToolRegistry {
    private final Map<String, PlatformToolHandler> handlers = new ConcurrentHashMap<>();

    public void register(String functionName, PlatformToolHandler handler) {
        if (functionName == null || functionName.isBlank() || handler == null
                || handlers.putIfAbsent(functionName, handler) != null) {
            throw new AppException("PLATFORM_TOOL_REGISTRATION_CONFLICT", "平台工具处理器注册冲突");
        }
    }

    public PlatformToolResult dispatch(ToolCatalogEntity tool, Map<String, Object> input, ToolInvokeContextEntity context) {
        PlatformToolHandler handler = handlers.get(tool == null ? null : tool.getFunctionName());
        if (handler == null) throw new AppException("PLATFORM_TOOL_HANDLER_MISSING", "平台工具处理器未注册");
        return handler.handle(tool, input, context);
    }
}
