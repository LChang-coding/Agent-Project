package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 保存并分发平台内置工具处理器。
 *
 * <p>注册表按函数名建立唯一映射。重复注册会在应用装配阶段直接失败，避免运行时随机选择处理器。</p>
 */
@Component
public class PlatformToolRegistry {

    /** 函数名到处理器的并发安全映射；应用启动后主要执行读取操作。 */
    private final Map<String, PlatformToolHandler> handlers = new ConcurrentHashMap<>();

    /**
     * 注册一个函数名对应的处理器。
     *
     * @param functionName 提供给模型调用的平台函数名
     * @param handler 负责执行该函数的处理器
     * @throws AppException 函数名无效、处理器为空或同名函数已注册时抛出
     */
    public void register(String functionName, PlatformToolHandler handler) {
        if (functionName == null || functionName.isBlank() || handler == null
                || handlers.putIfAbsent(functionName, handler) != null) {
            throw new AppException("PLATFORM_TOOL_REGISTRATION_CONFLICT", "平台工具处理器注册冲突");
        }
    }

    /**
     * 根据工具目录中的函数名分发调用。
     *
     * @param tool 已通过工具解析和权限校验的平台工具目录项
     * @param input 模型提交的工具参数
     * @param context 当前运行的可信上下文
     * @return 处理器返回的平台工具结果
     * @throws AppException 找不到对应处理器时抛出
     */
    public PlatformToolResult dispatch(ToolCatalogEntity tool, Map<String, Object> input, ToolInvokeContextEntity context) {
        PlatformToolHandler handler = handlers.get(tool == null ? null : tool.getFunctionName());
        if (handler == null) throw new AppException("PLATFORM_TOOL_HANDLER_MISSING", "平台工具处理器未注册");
        return handler.handle(tool, input, context);
    }
}
