package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;

import java.util.Map;

/**
 * 平台内置工具的执行接口。
 *
 * <p>实现类只接收服务端解析出的工具目录和可信运行上下文，不能从模型参数中读取租户、用户或运行身份。</p>
 */
@FunctionalInterface
public interface PlatformToolHandler {

    /**
     * 执行一次平台工具调用。
     *
     * @param tool 已由服务端解析并确认可用的平台工具
     * @param input 模型提交且经过工具参数边界校验的输入
     * @param context 当前运行的可信身份、会话和工作流上下文
     * @return 同时包含模型可见结果和审计结果的执行结果
     */
    PlatformToolResult handle(ToolCatalogEntity tool, Map<String, Object> input, ToolInvokeContextEntity context);
}
