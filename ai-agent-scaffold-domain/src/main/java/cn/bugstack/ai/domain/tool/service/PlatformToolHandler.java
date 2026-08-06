package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;

import java.util.Map;

@FunctionalInterface
public interface PlatformToolHandler {
    PlatformToolResult handle(ToolCatalogEntity tool, Map<String, Object> input, ToolInvokeContextEntity context);
}
