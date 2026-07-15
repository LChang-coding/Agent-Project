package cn.bugstack.ai.domain.tool.model.entity;

import lombok.Builder;
import lombok.Data;

/**
 * 会话工具调用聚合统计。
 */
@Data
@Builder
public class ToolCallStatisticsEntity {
    private Long callCount;
    private Long toolCount;
}
