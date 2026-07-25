package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.tool.adapter.repository.IToolRepository;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolUserContextEntity;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工具解析器。
 * <p>负责按照当前租户和用户身份，查询 Agent 本轮可以加载的 Skill/MCP 工具目录。</p>
 */
@Service
public class ToolResolver {

    /** 权限过滤在仓储查询中与租户范围同时执行。 */
    private final IToolRepository toolRepository;

    /**
     * 创建工具解析器；参数是工具仓储；返回解析器实例。
     */
    public ToolResolver(IToolRepository toolRepository) {
        this.toolRepository = toolRepository;
    }

    /** 身份不完整时失败关闭；完整时只查询已发布且可见的目录。 */
    public List<ToolCatalogEntity> resolve(ToolUserContextEntity context) {
        if (context == null || blank(context.getTenantId()) || blank(context.getUserId())) {
            throw new AppException("TOOL_CONTEXT_INVALID", "工具运行身份不完整");
        }
        return toolRepository.queryAvailableTools(context.getTenantId(), context.getUserId());
    }

    /**
     * 判断字符串是否为空；参数是字符串；返回是否为空。
     */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
